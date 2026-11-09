import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.time.LocalDate
import java.util.concurrent.Executors

// Pasta do programa: a que contém "web/index.html" (funciona rodando pela IDE ou pelo .bat)
fun acharPastaBase(): File {
    val atual = File(System.getProperty("user.dir"))
    val candidatas = listOf(atual, File(atual, "Calculadora bila"), atual.parentFile, atual.parentFile?.let { File(it, "Calculadora bila") })
    return candidatas.filterNotNull().firstOrNull { File(it, "web/index.html").exists() }
        ?: error("Não encontrei a pasta 'web' (index.html). Rode o programa de dentro da pasta do projeto.")
}

fun ipsDaRede(): List<String> = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }
        .filter { it is java.net.Inet4Address && it.isSiteLocalAddress }
        .map { it.hostAddress }
} catch (_: Exception) { emptyList() }

fun main(args: Array<String>) {
    val base = acharPastaBase()
    val arm = Armazem(base)
    val servico = Servico(arm)
    val cfg = arm.config()
    val enderecoLocal: InetAddress = if (cfg.acessoRede) InetAddress.getByName("0.0.0.0") else InetAddress.getLoopbackAddress()

    var servidor: HttpServer? = null
    var porta = 8080
    while (servidor == null && porta < 8100) {
        try { servidor = HttpServer.create(InetSocketAddress(enderecoLocal, porta), 0) } catch (_: BindException) { porta++ }
    }
    if (servidor == null) error("Não consegui abrir nenhuma porta entre 8080 e 8099.")

    val portaFinal = porta
    servidor.createContext("/") { ex -> atender(ex, base, arm, servico) }
    servidor.executor = Executors.newFixedThreadPool(4)
    servidor.start()

    val url = "http://localhost:$portaFinal"
    println()
    println("  Calculadora da Bila rodando em  $url")
    if (cfg.acessoRede) ipsDaRede().forEach { println("  No celular (mesma rede Wi-Fi):  http://$it:$portaFinal") }
    println("  Dados salvos em: ${File(base, "data").absolutePath}")
    println("  Para encerrar, feche esta janela (ou Ctrl+C).")
    println()

    if ("--sem-navegador" !in args) {
        try { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
        catch (_: Throwable) { println("  Abra o endereço acima no navegador.") }
    }
}

// ── Roteamento ────────────────────────────────────────────────────────────────
private fun atender(ex: HttpExchange, base: File, arm: Armazem, sv: Servico) {
    try {
        ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.responseHeaders.add("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; frame-ancestors 'none'")

        verificarOrigem(ex, arm.config().acessoRede)

        val metodo = ex.requestMethod
        val caminho = ex.requestURI.path
        if (metodo == "GET" && (caminho == "/" || caminho == "/index.html")) {
            responder(ex, 200, File(base, "web/index.html").readBytes(), "text/html; charset=utf-8")
            return
        }
        if (!caminho.startsWith("/api/")) throw ErroNegocio(404, "Não encontrado.")

        val partes = caminho.removePrefix("/api/").split('/').filter { it.isNotEmpty() }
        val corpo: Map<*, *> = if (metodo == "POST" || metodo == "PUT") lerCorpo(ex) else emptyMap<String, Any?>()
        val resposta: Any? = rota(metodo, partes, corpo, arm, sv)
        responder(ex, 200, Json.escrever(resposta).toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")
    } catch (e: ErroNegocio) {
        val m = linkedMapOf<String, Any?>("erro" to e.message)
        m.putAll(e.extra)
        responder(ex, e.status, Json.escrever(m).toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")
    } catch (e: Exception) {
        e.printStackTrace()
        responder(ex, 500, Json.escrever(mapOf("erro" to "Erro interno: ${e.message}")).toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")
    } finally {
        ex.close()
    }
}

private fun rota(metodo: String, p: List<String>, corpo: Map<*, *>, arm: Armazem, sv: Servico): Any? {
    fun num(k: String): Double = (corpo[k] as? Number)?.toDouble() ?: throw ErroNegocio(400, "Campo \"$k\" ausente ou inválido.")
    fun txt(k: String): String = corpo[k] as? String ?: ""

    when {
        // Informações gerais
        metodo == "GET" && p == listOf("info") -> {
            val c = arm.config()
            return mapOf("hoje" to LocalDate.now().toString(), "ips" to (if (c.acessoRede) ipsDaRede() else emptyList()))
        }
        metodo == "GET" && p == listOf("config") -> return sv.configParaTela()
        metodo == "PUT" && p == listOf("config") -> {
            val tabela = (corpo["tabela"] as? List<*>)?.map {
                val l = it as? Map<*, *> ?: throw ErroNegocio(400, "Tabela inválida.")
                val q = (l["qtd"] as? Number)?.toDouble(); val v = (l["valor"] as? Number)?.toDouble()
                if (q == null || v == null) throw ErroNegocio(400, "Tabela inválida.")
                q to v
            } ?: emptyList()
            sv.salvarConfig(num("preco"), num("saldoInicial"), corpo["acessoRede"] as? Boolean ?: false, tabela)
            return mapOf("ok" to true)
        }
        // Mês
        metodo == "GET" && p.size == 2 && p[0] == "mes" -> return sv.resumoMes(p[1])
        metodo == "GET" && p.size == 3 && p[0] == "mes" && p[2] == "relatorio" -> return mapOf("texto" to sv.relatorio(p[1]))
        metodo == "POST" && p.size == 3 && p[0] == "mes" && p[2] == "entradas" ->
            return sv.salvarEntrada(p[1], num("dia").toInt(), txt("qtd"), corpo["substituir"] as? Boolean ?: false)
        metodo == "DELETE" && p.size == 4 && p[0] == "mes" && p[2] == "entradas" -> {
            sv.apagarEntrada(p[1], p[3].toIntOrNull() ?: throw ErroNegocio(400, "Dia inválido."))
            return mapOf("ok" to true)
        }
        // Pagamentos
        metodo == "POST" && p == listOf("pagamentos") -> {
            sv.salvarPagamento(txt("data"), num("valor"), txt("obs"))
            return mapOf("ok" to true)
        }
        metodo == "DELETE" && p.size == 3 && p[0] == "pagamentos" -> {
            sv.apagarPagamento(p[1].also { arm.validarChave(it) }, p[2].toLongOrNull() ?: throw ErroNegocio(400, "Pagamento inválido."))
            return mapOf("ok" to true)
        }
        // Resumo geral
        metodo == "GET" && p == listOf("resumo") -> return sv.resumoGeral()
    }
    throw ErroNegocio(404, "Não encontrado.")
}

// ── Segurança básica (o servidor só atende a própria página) ──────────────────
private fun verificarOrigem(ex: HttpExchange, acessoRede: Boolean) {
    val host = ex.requestHeaders.getFirst("Host") ?: throw ErroNegocio(400, "Requisição inválida.")
    val nomeHost = host.substringBeforeLast(':').removeSurrounding("[", "]")
    if (!acessoRede && nomeHost !in setOf("localhost", "127.0.0.1", "::1"))
        throw ErroNegocio(403, "Acesso não permitido.")
    val origem = ex.requestHeaders.getFirst("Origin")
    if (origem != null && origem != "http://$host") throw ErroNegocio(403, "Origem não permitida.")
    if (ex.requestMethod in setOf("POST", "PUT")) {
        val tipo = ex.requestHeaders.getFirst("Content-Type") ?: ""
        if (!tipo.startsWith("application/json")) throw ErroNegocio(415, "Tipo de conteúdo não aceito.")
    }
}

private fun lerCorpo(ex: HttpExchange): Map<*, *> {
    val txt = ex.requestBody.readNBytes(100_000).toString(Charsets.UTF_8)
    if (txt.isBlank()) return emptyMap<String, Any?>()
    return try { Json.parse(txt) as? Map<*, *> ?: throw ErroNegocio(400, "Dados inválidos.") }
    catch (e: IllegalArgumentException) { throw ErroNegocio(400, "Dados inválidos.") }
}

private fun responder(ex: HttpExchange, status: Int, bytes: ByteArray, tipo: String) {
    ex.responseHeaders.set("Content-Type", tipo)
    ex.sendResponseHeaders(status, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}
