package com.novaprod.dropthink.util

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.*

object Calc {
    fun eval(input: String): Double {
        val p = Parser(input.replace("×", "*").replace("÷", "/").replace("**", "^"))
        val v = p.expr()
        p.skip()
        if (p.pos < p.s.length) throw IllegalArgumentException("Unexpected '${p.s[p.pos]}' at position ${p.pos}")
        if (v.isNaN() || v.isInfinite()) throw ArithmeticException("Result is not a finite number")
        return v
    }

    fun format(v: Double): String {
        if (v == floor(v) && abs(v) < 1e15) return v.toLong().toString()
        return BigDecimal(v).round(MathContext(12)).stripTrailingZeros().toPlainString()
    }

    class Parser(val s: String) {
        var pos = 0
        fun skip() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        private fun peek(): Char { skip(); return if (pos < s.length) s[pos] else '\u0000' }

        fun expr(): Double {
            var v = term()
            while (true) {
                val c = peek()
                if (c == '+') { pos++; v += term() } else if (c == '-') { pos++; v -= term() } else return v
            }
        }

        private fun term(): Double {
            var v = factor()
            while (true) {
                val c = peek()
                if (c == '*') { pos++; v *= factor() }
                else if (c == '/') { pos++; val d = factor(); if (d == 0.0) throw ArithmeticException("Division by zero"); v /= d }
                else if (c == '%') { pos++; v %= factor() }
                else return v
            }
        }

        private fun factor(): Double {
            val b = unary()
            if (peek() == '^') { pos++; return b.pow(factor()) }
            return b
        }

        private fun unary(): Double {
            val c = peek()
            if (c == '-') { pos++; return -unary() }
            if (c == '+') { pos++; return unary() }
            return primary()
        }

        private fun primary(): Double {
            val c = peek()
            if (c == '(') {
                pos++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException("Missing ')'")
                pos++
                return v
            }
            if (c.isDigit() || c == '.') {
                val st = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E') && pos + 1 < s.length && (s[pos + 1].isDigit() || s[pos + 1] == '-' || s[pos + 1] == '+')) {
                    pos += 2
                    while (pos < s.length && s[pos].isDigit()) pos++
                }
                return s.substring(st, pos).toDoubleOrNull() ?: throw IllegalArgumentException("Bad number '${s.substring(st, pos)}'")
            }
            if (c.isLetter()) {
                val st = pos
                while (pos < s.length && s[pos].isLetter()) pos++
                val name = s.substring(st, pos).lowercase()
                if (name == "pi") return PI
                if (name == "e") return E
                if (peek() != '(') throw IllegalArgumentException("Unknown name '$name'")
                pos++
                val a = expr()
                var b: Double? = null
                if (peek() == ',') { pos++; b = expr() }
                if (peek() != ')') throw IllegalArgumentException("Missing ')'")
                pos++
                return when (name) {
                    "sqrt" -> sqrt(a)
                    "sin" -> sin(a)
                    "cos" -> cos(a)
                    "tan" -> tan(a)
                    "ln" -> ln(a)
                    "log" -> if (b != null) ln(a) / ln(b) else log10(a)
                    "abs" -> abs(a)
                    "round" -> a.roundToLong().toDouble()
                    "floor" -> floor(a)
                    "ceil" -> ceil(a)
                    "pow" -> a.pow(b ?: throw IllegalArgumentException("pow needs two arguments"))
                    else -> throw IllegalArgumentException("Unknown function '$name'")
                }
            }
            throw IllegalArgumentException(if (c == '\u0000') "Unexpected end of expression" else "Unexpected '$c'")
        }
    }
}
