import java.util.Locale

// ── Cores ANSI ────────────────────────────────────────────────────────────────
object C {
    const val RESET   = "\u001B[0m"
    const val BOLD    = "\u001B[1m"
    const val VERDE   = "\u001B[32m"
    const val AMARELO = "\u001B[33m"
    const val CIANO   = "\u001B[36m"
    const val VERMELHO= "\u001B[31m"
    const val BRANCO  = "\u001B[97m"
    const val ROXO    = "\u001B[35m"
}

// ── Preços ────────────────────────────────────────────────────────────────────
val baldes = listOf(
    Triple("Meio Balde",   "½",  17.50),
    Triple("Um Balde",     "1",  35.00),
    Triple("Um Balde e meio, Dois Baldes",  "2",  70.00),
    Triple("Três Baldes",  "3",  105.00),
)

val localeBR = Locale.forLanguageTag("pt-BR")

// ── Formatação ────────────────────────────────────────────────────────────────
fun moeda(valor: Double): String = "R$ %,.2f".format(valor).replace(",", "X").replace(".", ",").replace("X", ".")

fun limparTela() = print("\u001B[2J\u001B[H")

// ── Header ────────────────────────────────────────────────────────────────────
fun exibirHeader() {
    println()
    println("${C.CIANO}${C.BOLD}  ╔══════════════════════════════════╗${C.RESET}")
    println("${C.CIANO}${C.BOLD}  ║   \uD83E\uDDF4  Calculadora da Bila        ║${C.RESET}")
    println("${C.CIANO}${C.BOLD}  ║       Produção de Baldes         ║${C.RESET}")
    println("${C.CIANO}${C.BOLD}  ╚══════════════════════════════════╝${C.RESET}")
    println()
}

// ── Leitura com validação ─────────────────────────────────────────────────────
fun lerQuantidade(label: String, icone: String): Int {
    while (true) {
        print("  ${C.AMARELO}${C.BOLD}$icone  Quantidade de $label: ${C.RESET}")
        val entrada = readln().trim()
        val valor = entrada.toIntOrNull()
        when {
            valor == null -> println("  ${C.VERMELHO}✗  Digite apenas números inteiros.${C.RESET}")
            valor < 0     -> println("  ${C.VERMELHO}✗  A quantidade não pode ser negativa.${C.RESET}")
            else          -> { println(); return valor }
        }
    }
}

// ── Subtração de valores recebidos ────────────────────────────────────────────
fun lerValorMonetario(label: String): Double {
    while (true) {
        print("  ${C.AMARELO}${C.BOLD}💰  $label: ${C.RESET}")
        val entrada = readln().trim().replace(",", ".")
        val valor = entrada.toDoubleOrNull()
        when {
            valor == null -> println("  ${C.VERMELHO}✗  Digite um valor numérico válido (ex: 35 ou 17,50).${C.RESET}")
            valor < 0     -> println("  ${C.VERMELHO}✗  O valor não pode ser negativo.${C.RESET}")
            else          -> { println(); return valor }
        }
    }
}

fun exibirAbatimento(total: Double) {
    val recebido = lerValorMonetario("Valor recebido para abater a dívida")
    val saldo = total - recebido
    val largura = 45

    println("  ${C.ROXO}${C.BOLD}┌${"─".repeat(largura)}┐${C.RESET}")
    println("  ${C.ROXO}${C.BOLD}│${"ABATIMENTO DA DÍVIDA".padStart((largura + 20) / 2).padEnd(largura)}│${C.RESET}")
    println("  ${C.ROXO}${C.BOLD}├${"─".repeat(largura)}┤${C.RESET}")

    val totalLabel   = " Total da dívida:    "
    val totalStr     = moeda(total)
    val espTotal     = largura - totalLabel.length - totalStr.length - 1
    println("  ${C.BRANCO}${C.BOLD}│$totalLabel${" ".repeat(espTotal)}$totalStr │${C.RESET}")

    val recLabel     = " Valor recebido:     "
    val recStr       = moeda(recebido)
    val espRec       = largura - recLabel.length - recStr.length - 1
    println("  ${C.BRANCO}${C.BOLD}│$recLabel${" ".repeat(espRec)}$recStr │${C.RESET}")

    println("  ${C.ROXO}${C.BOLD}├${"─".repeat(largura)}┤${C.RESET}")

    if (saldo <= 0) {
        val quitadoStr = if (saldo < 0) "(sobra ${moeda(-saldo)})" else ""
        val msg = " Dívida QUITADA! $quitadoStr"
        println("  ${C.VERDE}${C.BOLD}│$msg${" ".repeat(largura - msg.length)} │${C.RESET}")
    } else {
        val saldoLabel = " Saldo restante:     "
        val saldoStr   = moeda(saldo)
        val espSaldo   = largura - saldoLabel.length - saldoStr.length - 1
        println("  ${C.VERMELHO}${C.BOLD}│$saldoLabel${" ".repeat(espSaldo)}$saldoStr │${C.RESET}")
    }

    println("  ${C.ROXO}${C.BOLD}└${"─".repeat(largura)}┘${C.RESET}")
    println()
}

// ── Tabela de resultado ───────────────────────────────────────────────────────
fun exibirTabela(quantidades: List<Int>): Double {
    val largura = 45

    println()
    println("  ${C.VERDE}${C.BOLD}┌${"─".repeat(largura)}┐${C.RESET}")
    println("  ${C.VERDE}${C.BOLD}│${"RESUMO DO DIA".padStart((largura + 13) / 2).padEnd(largura)}│${C.RESET}")
    println("  ${C.VERDE}${C.BOLD}├${"─".repeat(18)}┬${"─".repeat(8)}┬${"─".repeat(largura - 27)}┤${C.RESET}")
    println("  ${C.VERDE}${C.BOLD}│ ${"Tipo".padEnd(16)} │ ${"Qtd".padEnd(6)} │ ${"Valor".padEnd(largura - 30)} │${C.RESET}")
    println("  ${C.VERDE}${C.BOLD}├${"─".repeat(18)}┼${"─".repeat(8)}┼${"─".repeat(largura - 27)}┤${C.RESET}")

    var total = 0.0
    baldes.forEachIndexed { i, (nome, _, preco) ->
        val qtd = quantidades[i]
        val subtotal = qtd * preco
        total += subtotal
        val nomeCol  = nome.padEnd(16)
        val qtdCol   = qtd.toString().padStart(4).padEnd(6)
        val valorCol = moeda(subtotal).padStart(largura - 30)
        println("  ${C.BRANCO}│ $nomeCol │ $qtdCol │ $valorCol │${C.RESET}")
    }

    println("  ${C.VERDE}${C.BOLD}├${"─".repeat(18)}┴${"─".repeat(8)}┴${"─".repeat(largura - 27)}┤${C.RESET}")

    val totalStr = moeda(total)
    val espaco = largura - 6 - totalStr.length - 1
    println("  ${C.VERDE}${C.BOLD}│ TOTAL${" ".repeat(espaco)}$totalStr │${C.RESET}")
    println("  ${C.VERDE}${C.BOLD}└${"─".repeat(largura)}┘${C.RESET}")
    println()
    return total
}

// ── Pergunta de repetição ─────────────────────────────────────────────────────
fun perguntarReiniciar(): Boolean {
    while (true) {
        print("  ${C.ROXO}${C.BOLD}↩  Calcular outro dia? (s/n): ${C.RESET}")
        when (readln().trim().lowercase()) {
            "s", "sim" -> { println(); return true  }
            "n", "nao", "não" -> return false
            else -> println("  ${C.VERMELHO}✗  Digite 's' para sim ou 'n' para não.${C.RESET}")
        }
    }
}

// ── Main ──────────────────────────────────────────────────────────────────────
fun main() {
    do {
        limparTela()
        exibirHeader()

        val quantidades = baldes.map { (nome, icone, _) -> lerQuantidade(nome, icone) }

        val total = exibirTabela(quantidades)
        exibirAbatimento(total)
    } while (perguntarReiniciar())

    println()
    println("  ${C.CIANO}${C.BOLD}Até logo! Bom trabalho hoje. \uD83C\uDF1F${C.RESET}")
    println()
}
