import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.YearMonth

// ── Erro de regra de negócio (vira resposta HTTP) ────────────────────────────
class ErroNegocio(val status: Int, mensagem: String, val extra: Map<String, Any?> = emptyMap()) : Exception(mensagem)

// ── Modelo ────────────────────────────────────────────────────────────────────
class Config(
    var preco: Double = 35.0,          // preço de 1 balde
    var saldoInicial: Double = 0.0,    // quanto já faltava receber antes do primeiro lançamento
    var acessoRede: Boolean = false,   // permitir abrir pelo celular na mesma rede
    val valores: MutableMap<Double, Double> = mutableMapOf(),  // valor de um dia por quantidade, quando diferente do padrão
) {
    /** Quanto vale um dia com [qtd] baldes. */
    fun valorDoDia(qtd: Double): Double = valores[qtd] ?: valorPadrao(qtd, preco)
}

/**
 * Regra padrão (a que a família usa hoje):
 *  - meio balde sozinho: metade do preço;
 *  - 1 e 1½ valem o mesmo (o meio não conta);
 *  - 2 ou mais: preço × baldes inteiros; o meio vale metade do preço (equivale a juntar 2 meios em 1 balde).
 */
fun valorPadrao(qtd: Double, preco: Double): Double {
    val inteiros = Math.floor(qtd)
    val temMeio = qtd - inteiros >= 0.5
    val v = when {
        inteiros == 0.0 -> preco / 2
        inteiros == 1.0 -> preco
        else -> inteiros * preco + (if (temMeio) preco / 2 else 0.0)
    }
    return r2(v)
}

/** Quantidades que aparecem na tabela de Ajustes: ½, 1, 1½ … 6. */
val QUANTIDADES_TABELA: List<Double> = (1..12).map { it * 0.5 }

class Entrada(val dia: Int, val baldes: Double, val preco: Double, val valor: Double)

class Pagamento(val id: Long, val data: String, val valor: Double, val obs: String)

class Mes(val chave: String, val entradas: MutableList<Entrada> = mutableListOf(), val pagamentos: MutableList<Pagamento> = mutableListOf()) {
    val ganho: Double get() = r2(entradas.sumOf { it.valor })
    val pago: Double get() = r2(pagamentos.sumOf { it.valor })
    val baldes: Double get() = entradas.sumOf { it.baldes }
    val vazio: Boolean get() = entradas.isEmpty() && pagamentos.isEmpty()
}

fun r2(x: Double): Double = Math.round(x * 100.0) / 100.0

// ── Formatação em português ───────────────────────────────────────────────────
val NOMES_MESES = listOf("Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho", "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro")

fun moeda(v: Double): String {
    val negativo = v < 0
    val cents = Math.round(Math.abs(v) * 100.0)
    val inteiro = (cents / 100).toString().reversed().chunked(3).joinToString(".").reversed()
    return (if (negativo) "-" else "") + "R$ $inteiro,%02d".format(cents % 100)
}

fun qtdTexto(b: Double): String =
    if (b == Math.rint(b)) b.toLong().toString() else b.toString().replace('.', ',')

fun nomeMes(chave: String): String {
    val ym = YearMonth.parse(chave)
    return "${NOMES_MESES[ym.monthValue - 1]}/${ym.year}"
}

// ── Leitura do que foi digitado, no estilo do caderno ─────────────────────────
// "///" = 3   "//m" = 2 e meio   "m" = meio   "2,5" = 2 e meio   "1 meio" = 1 e meio
object Quantidade {
    private val regex = Regex("""^(\d+(?:[.,]\d+)?)?\s*([/|]*)\s*(m|meio|meia|½)?$""")

    fun ler(texto: String): Double {
        val t = texto.trim().lowercase()
        if (t.isEmpty()) throw ErroNegocio(400, "Digite a quantidade de baldes (ex: // ou //m ou 2,5).")
        val m = regex.matchEntire(t) ?: throw ErroNegocio(400, "Não entendi a quantidade \"$texto\". Use traços (//), \"m\" para meio (//m) ou número (2,5).")
        val numero = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
        val tracos = m.groupValues[2].length
        val meio = if (m.groupValues[3].isNotEmpty()) 0.5 else 0.0
        val total = numero + tracos + meio
        if (total <= 0) throw ErroNegocio(400, "A quantidade precisa ser maior que zero.")
        if (total > 200) throw ErroNegocio(400, "Quantidade muito grande. Confira o valor digitado.")
        if (total * 2 != Math.rint(total * 2)) throw ErroNegocio(400, "Use apenas baldes inteiros ou com meio (ex: 2 ou 2,5).")
        return total
    }
}

// ── Armazenamento em arquivos ─────────────────────────────────────────────────
class Armazem(private val base: File) {
    private val pasta = File(base, "data").also { it.mkdirs() }
    private val pastaBackup = File(pasta, "backup")
    private val arquivoConfig = File(pasta, "config.json")
    private val regexChave = Regex("""^\d{4}-(0[1-9]|1[0-2])$""")

    fun validarChave(chave: String) {
        if (!regexChave.matches(chave)) throw ErroNegocio(400, "Mês inválido. Use o formato AAAA-MM.")
    }

    // ── config ──
    @Synchronized fun config(): Config {
        if (!arquivoConfig.exists()) return Config()
        val m = lerArquivo(arquivoConfig) as? Map<*, *> ?: return Config()
        val cfg = Config(
            preco = (m["preco"] as? Number)?.toDouble() ?: 35.0,
            saldoInicial = (m["saldoInicial"] as? Number)?.toDouble() ?: 0.0,
            acessoRede = m["acessoRede"] as? Boolean ?: false,
        )
        (m["valores"] as? Map<*, *>)?.forEach { (k, v) ->
            val q = (k as? String)?.toDoubleOrNull(); val x = (v as? Number)?.toDouble()
            if (q != null && x != null) cfg.valores[q] = x
        }
        return cfg
    }

    @Synchronized fun salvarConfig(c: Config) {
        fazerBackupDoDia()
        gravar(arquivoConfig, mapOf(
            "preco" to c.preco, "saldoInicial" to c.saldoInicial, "acessoRede" to c.acessoRede,
            "valores" to c.valores.toSortedMap().mapKeys { it.key.toString() },
        ))
    }

    // ── meses ──
    @Synchronized fun chaves(): List<String> =
        (pasta.listFiles() ?: emptyArray()).map { it.name }
            .filter { it.endsWith(".json") && regexChave.matches(it.removeSuffix(".json")) }
            .map { it.removeSuffix(".json") }.sorted()

    @Synchronized fun mes(chave: String): Mes {
        validarChave(chave)
        val arq = File(pasta, "$chave.json")
        val mes = Mes(chave)
        if (!arq.exists()) return mes
        val m = lerArquivo(arq) as? Map<*, *> ?: return mes
        (m["entradas"] as? List<*>)?.forEach {
            val e = it as Map<*, *>
            val baldes = (e["baldes"] as Number).toDouble()
            val preco = (e["preco"] as Number).toDouble()
            // arquivos antigos não têm "valor": usa a regra padrão com o preço da época
            val valor = (e["valor"] as? Number)?.toDouble() ?: valorPadrao(baldes, preco)
            mes.entradas.add(Entrada((e["dia"] as Number).toInt(), baldes, preco, valor))
        }
        (m["pagamentos"] as? List<*>)?.forEach {
            val p = it as Map<*, *>
            mes.pagamentos.add(Pagamento((p["id"] as Number).toLong(), p["data"] as String, (p["valor"] as Number).toDouble(), p["obs"] as? String ?: ""))
        }
        mes.entradas.sortBy { it.dia }
        mes.pagamentos.sortWith(compareBy({ it.data }, { it.id }))
        return mes
    }

    @Synchronized fun salvarMes(mes: Mes) {
        fazerBackupDoDia()
        val arq = File(pasta, "${mes.chave}.json")
        if (mes.vazio) { arq.delete(); return }
        gravar(arq, mapOf(
            "entradas" to mes.entradas.sortedBy { it.dia }.map { mapOf("dia" to it.dia, "baldes" to it.baldes, "preco" to it.preco, "valor" to it.valor) },
            "pagamentos" to mes.pagamentos.map { mapOf("id" to it.id, "data" to it.data, "valor" to it.valor, "obs" to it.obs) },
        ))
    }

    // ── infra ──
    private fun lerArquivo(arq: File): Any? =
        try { Json.parse(arq.readText(Charsets.UTF_8)) }
        catch (e: Exception) { throw ErroNegocio(500, "O arquivo ${arq.name} está ilegível (${e.message}). Ele NÃO foi alterado; restaure de data/backup.") }

    private fun gravar(arq: File, conteudo: Any?) {
        val tmp = File(arq.parentFile, arq.name + ".tmp")
        tmp.writeText(Json.escrever(conteudo), Charsets.UTF_8)
        Files.move(tmp.toPath(), arq.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    // Uma cópia de tudo por dia (na primeira gravação do dia). Guarda as últimas 30.
    private fun fazerBackupDoDia() {
        try {
            val destino = File(pastaBackup, LocalDate.now().toString())
            if (destino.exists()) return
            val origem = (pasta.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".json") }
            if (origem.isEmpty()) return
            destino.mkdirs()
            origem.forEach { Files.copy(it.toPath(), File(destino, it.name).toPath(), StandardCopyOption.REPLACE_EXISTING) }
            (pastaBackup.listFiles() ?: emptyArray()).filter { it.isDirectory }.sortedBy { it.name }
                .dropLast(30).forEach { it.deleteRecursively() }
        } catch (_: Exception) { /* backup nunca deve impedir o uso */ }
    }
}

// ── Regras / cálculos ─────────────────────────────────────────────────────────
class Servico(private val arm: Armazem) {

    @Synchronized
    fun salvarEntrada(chave: String, dia: Int, qtd: String, substituir: Boolean): Map<String, Any?> {
        arm.validarChave(chave)
        val ym = YearMonth.parse(chave)
        if (dia < 1 || dia > ym.lengthOfMonth())
            throw ErroNegocio(400, "O dia $dia não existe em ${nomeMes(chave)} (vai de 1 a ${ym.lengthOfMonth()}).")
        val baldes = Quantidade.ler(qtd)
        val mes = arm.mes(chave)
        val existente = mes.entradas.find { it.dia == dia }
        if (existente != null) {
            if (!substituir) throw ErroNegocio(409, "O dia $dia já tem ${qtdTexto(existente.baldes)} balde(s) lançado(s).", mapOf("existente" to existente.baldes))
            mes.entradas.remove(existente)
        }
        val cfg = arm.config()
        mes.entradas.add(Entrada(dia, baldes, cfg.preco, cfg.valorDoDia(baldes)))
        arm.salvarMes(mes)
        return mapOf("ok" to true, "baldes" to baldes)
    }

    @Synchronized
    fun apagarEntrada(chave: String, dia: Int) {
        val mes = arm.mes(chave)
        if (!mes.entradas.removeIf { it.dia == dia }) throw ErroNegocio(404, "Esse dia não está lançado.")
        arm.salvarMes(mes)
    }

    @Synchronized
    fun salvarPagamento(data: String, valor: Double, obs: String) {
        val d = try { LocalDate.parse(data) } catch (_: Exception) { throw ErroNegocio(400, "Data inválida.") }
        if (valor.isNaN() || valor <= 0) throw ErroNegocio(400, "O valor do pagamento precisa ser maior que zero.")
        if (valor > 10_000_000) throw ErroNegocio(400, "Valor muito grande. Confira o que foi digitado.")
        val chave = "%04d-%02d".format(d.year, d.monthValue)
        val mes = arm.mes(chave)
        mes.pagamentos.add(Pagamento(System.currentTimeMillis(), d.toString(), r2(valor), obs.trim().take(100)))
        arm.salvarMes(mes)
    }

    @Synchronized
    fun apagarPagamento(chave: String, id: Long) {
        val mes = arm.mes(chave)
        if (!mes.pagamentos.removeIf { it.id == id }) throw ErroNegocio(404, "Pagamento não encontrado.")
        arm.salvarMes(mes)
    }

    @Synchronized
    fun salvarConfig(preco: Double, saldoInicial: Double, acessoRede: Boolean, tabela: List<Pair<Double, Double>>) {
        if (preco.isNaN() || preco <= 0 || preco > 100_000) throw ErroNegocio(400, "Preço do balde inválido.")
        if (saldoInicial.isNaN() || Math.abs(saldoInicial) > 100_000_000) throw ErroNegocio(400, "Saldo inicial inválido.")
        val novoPreco = r2(preco)
        val valores = mutableMapOf<Double, Double>()
        for ((qtd, valor) in tabela) {
            if (qtd !in QUANTIDADES_TABELA) throw ErroNegocio(400, "Quantidade fora da tabela.")
            if (valor.isNaN() || valor < 0 || valor > 1_000_000) throw ErroNegocio(400, "Valor inválido para ${qtdTexto(qtd)} balde(s).")
            // só guarda o que foge da regra padrão; o resto acompanha o preço do balde
            if (r2(valor) != valorPadrao(qtd, novoPreco)) valores[qtd] = r2(valor)
        }
        arm.salvarConfig(Config(novoPreco, r2(saldoInicial), acessoRede, valores))
    }

    /** Linhas da tabela de Ajustes: valor atual e valor padrão de cada tipo de dia. */
    @Synchronized
    fun configParaTela(): Map<String, Any?> {
        val c = arm.config()
        return mapOf(
            "preco" to c.preco, "saldoInicial" to c.saldoInicial, "acessoRede" to c.acessoRede,
            "tabela" to QUANTIDADES_TABELA.map { q ->
                mapOf("qtd" to q, "valor" to c.valorDoDia(q), "padrao" to valorPadrao(q, c.preco))
            },
        )
    }

    /** Saldo (a receber) logo antes do início do mês: saldo inicial + tudo que foi feito menos o que foi pago nos meses anteriores. */
    private fun saldoAnterior(chave: String): Double {
        var s = arm.config().saldoInicial
        for (k in arm.chaves()) if (k < chave) { val m = arm.mes(k); s += m.ganho - m.pago }
        return r2(s)
    }

    /** (quantidade, nº de dias, valor total) agrupado por tipo de dia. */
    private fun porTipo(mes: Mes): List<Triple<Double, Int, Double>> =
        mes.entradas.groupBy { it.baldes }.toSortedMap()
            .map { (q, lista) -> Triple(q, lista.size, r2(lista.sumOf { it.valor })) }

    @Synchronized
    fun resumoMes(chave: String): Map<String, Any?> {
        val mes = arm.mes(chave)
        val anterior = saldoAnterior(chave)
        val saldoFinal = r2(anterior + mes.ganho - mes.pago)
        val dias = mes.entradas.size
        return mapOf(
            "chave" to chave,
            "nome" to nomeMes(chave),
            "diasNoMes" to YearMonth.parse(chave).lengthOfMonth(),
            "entradas" to mes.entradas.map { mapOf("dia" to it.dia, "baldes" to it.baldes, "preco" to it.preco, "valor" to it.valor) },
            "pagamentos" to mes.pagamentos.map { mapOf("id" to it.id, "data" to it.data, "valor" to it.valor, "obs" to it.obs) },
            "porTipo" to porTipo(mes).map { mapOf("qtd" to it.first, "dias" to it.second, "valor" to it.third) },
            "totalBaldes" to mes.baldes,
            "ganho" to mes.ganho,
            "pago" to mes.pago,
            "diasTrabalhados" to dias,
            "mediaBaldes" to (if (dias > 0) Math.round(mes.baldes / dias * 100.0) / 100.0 else 0.0),
            "saldoAnterior" to anterior,
            "saldoFinal" to saldoFinal,
        )
    }

    @Synchronized
    fun resumoGeral(): Map<String, Any?> {
        val cfg = arm.config()
        var saldo = cfg.saldoInicial
        var baldes = 0.0; var ganho = 0.0; var pago = 0.0; var dias = 0
        val linhas = arm.chaves().map { k ->
            val m = arm.mes(k)
            saldo += m.ganho - m.pago
            baldes += m.baldes; ganho += m.ganho; pago += m.pago; dias += m.entradas.size
            mapOf("chave" to k, "nome" to nomeMes(k), "baldes" to m.baldes, "dias" to m.entradas.size,
                "ganho" to m.ganho, "pago" to m.pago, "saldoFinal" to r2(saldo))
        }
        return mapOf(
            "meses" to linhas.reversed(),
            "totalBaldes" to baldes, "ganho" to r2(ganho), "pago" to r2(pago), "diasTrabalhados" to dias,
            "saldoInicial" to cfg.saldoInicial, "saldo" to r2(saldo),
        )
    }

    /** Texto simples, pronto para mandar no WhatsApp. */
    @Synchronized
    fun relatorio(chave: String): String {
        val mes = arm.mes(chave)
        val r = resumoMes(chave)
        val sb = StringBuilder()
        sb.append("*Baldes — ${nomeMes(chave)}*\n\n")
        if (mes.entradas.isEmpty()) sb.append("(nenhum dia lançado)\n")
        for (e in mes.entradas) sb.append("Dia %02d: %s balde(s) — %s\n".format(e.dia, qtdTexto(e.baldes), moeda(e.valor)))
        if (mes.entradas.isNotEmpty()) {
            sb.append("\nPor tipo de dia:\n")
            for ((q, n, v) in porTipo(mes)) sb.append("- ${qtdTexto(q)} balde(s): $n dia(s) = ${moeda(v)}\n")
        }
        sb.append("\nTotal: ${qtdTexto(mes.baldes)} baldes = ${moeda(mes.ganho)}\n")
        sb.append("Dias trabalhados: ${mes.entradas.size}")
        if (mes.entradas.isNotEmpty()) sb.append(" (média ${qtdTexto(r["mediaBaldes"] as Double)} baldes/dia)")
        sb.append("\n")
        val anterior = r["saldoAnterior"] as Double
        if (anterior != 0.0) sb.append("\nSaldo anterior: ${moeda(anterior)}\n")
        if (mes.pagamentos.isNotEmpty()) {
            sb.append("\nRecebido:\n")
            for (p in mes.pagamentos) {
                val d = LocalDate.parse(p.data)
                sb.append("- %02d/%02d: %s%s\n".format(d.dayOfMonth, d.monthValue, moeda(p.valor), if (p.obs.isNotBlank()) " (${p.obs})" else ""))
            }
            sb.append("Total recebido: ${moeda(mes.pago)}\n")
        }
        val fim = r["saldoFinal"] as Double
        sb.append("\n")
        when {
            fim > 0 -> sb.append("*FALTA RECEBER: ${moeda(fim)}*")
            fim < 0 -> sb.append("*PAGO A MAIS: ${moeda(-fim)}*")
            else -> sb.append("*PAGO — nada a receber*")
        }
        return sb.toString()
    }
}
