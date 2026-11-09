// JSON mínimo, sem dependências externas.
// Valores: null, Boolean, Double, String, List<Any?>, Map<String, Any?>

object Json {
    fun parse(texto: String): Any? {
        val p = Parser(texto)
        p.espacos()
        val v = p.valor()
        p.espacos()
        if (!p.fim()) throw IllegalArgumentException("JSON inválido")
        return v
    }

    fun escrever(v: Any?, sb: StringBuilder = StringBuilder()): String {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int -> sb.append(v)
            is Long -> sb.append(v)
            is Double -> sb.append(numero(v))
            is Float -> sb.append(numero(v.toDouble()))
            is String -> texto(v, sb)
            is Map<*, *> -> {
                sb.append('{')
                var primeiro = true
                for ((k, x) in v) {
                    if (!primeiro) sb.append(',')
                    primeiro = false
                    texto(k.toString(), sb); sb.append(':'); escrever(x, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var primeiro = true
                for (x in v) {
                    if (!primeiro) sb.append(',')
                    primeiro = false
                    escrever(x, sb)
                }
                sb.append(']')
            }
            else -> texto(v.toString(), sb)
        }
        return sb.toString()
    }

    private fun numero(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "0"
        return if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }

    private fun texto(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun fim() = i >= s.length
        fun espacos() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun valor(): Any? {
            espacos()
            if (fim()) throw IllegalArgumentException("JSON incompleto")
            return when (val c = s[i]) {
                '{' -> objeto()
                '[' -> lista()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) numero() else throw IllegalArgumentException("JSON inválido")
            }
        }

        fun literal(txt: String, v: Any?): Any? {
            if (!s.startsWith(txt, i)) throw IllegalArgumentException("JSON inválido")
            i += txt.length
            return v
        }

        fun numero(): Double {
            val ini = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(ini, i).toDoubleOrNull() ?: throw IllegalArgumentException("Número inválido")
        }

        fun string(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                if (fim()) throw IllegalArgumentException("Texto sem fim")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (fim()) throw IllegalArgumentException("Texto sem fim")
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalArgumentException("Escape inválido")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun lista(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            espacos()
            if (!fim() && s[i] == ']') { i++; return l }
            while (true) {
                l.add(valor()); espacos()
                if (fim()) throw IllegalArgumentException("Lista sem fim")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                throw IllegalArgumentException("JSON inválido")
            }
        }

        fun objeto(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            espacos()
            if (!fim() && s[i] == '}') { i++; return m }
            while (true) {
                espacos()
                if (fim() || s[i] != '"') throw IllegalArgumentException("JSON inválido")
                val k = string(); espacos()
                if (fim() || s[i] != ':') throw IllegalArgumentException("JSON inválido")
                i++
                m[k] = valor(); espacos()
                if (fim()) throw IllegalArgumentException("Objeto sem fim")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                throw IllegalArgumentException("JSON inválido")
            }
        }
    }
}
