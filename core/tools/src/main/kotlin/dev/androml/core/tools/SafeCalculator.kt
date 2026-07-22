package dev.androml.core.tools

import kotlin.math.abs

/**
 * Small, allocation-bounded arithmetic evaluator for the built-in calculator tool.
 * It deliberately supports no names, calls, assignments, or implicit operations.
 */
object SafeCalculator {
    private const val MAX_EXPRESSION_LENGTH = 512
    private const val MAX_ABS_VALUE = 1e12

    fun evaluate(expression: String): Double {
        require(expression.length in 1..MAX_EXPRESSION_LENGTH) { "expression is out of bounds" }
        val parser = Parser(expression)
        val value = parser.parseExpression()
        parser.skipWhitespace()
        require(parser.atEnd()) { "unexpected expression input" }
        require(value.isFinite() && abs(value) <= MAX_ABS_VALUE) { "calculation result is out of bounds" }
        return value
    }

    private class Parser(private val input: String) {
        private var index = 0

        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipWhitespace()
                value = when {
                    consume('+') -> value + parseTerm()
                    consume('-') -> value - parseTerm()
                    else -> return value
                }
                require(value.isFinite() && abs(value) <= MAX_ABS_VALUE) { "calculation result is out of bounds" }
            }
        }

        private fun parseTerm(): Double {
            var value = parseUnary()
            while (true) {
                skipWhitespace()
                value = when {
                    consume('*') -> value * parseUnary()
                    consume('/') -> {
                        val divisor = parseUnary()
                        require(divisor != 0.0) { "division by zero" }
                        value / divisor
                    }
                    else -> return value
                }
                require(value.isFinite() && abs(value) <= MAX_ABS_VALUE) { "calculation result is out of bounds" }
            }
        }

        private fun parseUnary(): Double {
            skipWhitespace()
            return when {
                consume('+') -> parseUnary()
                consume('-') -> -parseUnary()
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): Double {
            skipWhitespace()
            if (consume('(')) {
                val value = parseExpression()
                require(consume(')')) { "missing closing parenthesis" }
                return value
            }
            val start = index
            var decimal = false
            while (index < input.length) {
                val character = input[index]
                when {
                    character.isDigit() -> index += 1
                    character == '.' && !decimal -> {
                        decimal = true
                        index += 1
                    }
                    else -> break
                }
            }
            require(index > start) { "number expected" }
            return input.substring(start, index).toDoubleOrNull()
                ?: throw IllegalArgumentException("number is invalid")
        }

        fun skipWhitespace() {
            while (index < input.length && input[index].isWhitespace()) index += 1
        }

        fun atEnd(): Boolean = index >= input.length

        private fun consume(character: Char): Boolean {
            if (index < input.length && input[index] == character) {
                index += 1
                return true
            }
            return false
        }
    }
}
