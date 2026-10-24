package com.mitra.assistant

/** Chhota safe calculator (koi eval nahi, apna parser). */
object Calc {
    fun eval(input: String): Double? {
        val s = input.replace(" ", "")
        if (s.isEmpty()) return null
        return try {
            val p = Parser(s)
            val v = p.expr()
            if (p.pos != s.length) null else v
        } catch (e: Exception) {
            null
        }
    }

    private class Parser(val s: String) {
        var pos = 0

        fun expr(): Double {
            var v = term()
            while (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                val op = s[pos]; pos++
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        fun term(): Double {
            var v = factor()
            while (pos < s.length && (s[pos] == '*' || s[pos] == '/')) {
                val op = s[pos]; pos++
                val r = factor()
                v = if (op == '*') v * r else v / r
            }
            return v
        }

        fun factor(): Double {
            if (pos < s.length && s[pos] == '(') {
                pos++
                val v = expr()
                if (pos < s.length && s[pos] == ')') pos++
                return v
            }
            if (pos < s.length && s[pos] == '-') { pos++; return -factor() }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) throw RuntimeException("bad")
            return s.substring(start, pos).toDouble()
        }
    }
}
