package dev.androml.core.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeCalculatorTest {
    @Test
    fun evaluatesOnlyBoundedArithmeticWithPrecedence() {
        assertEquals(11.0, SafeCalculator.evaluate("2 + 3 * (4 - 1)"), 0.0)
        assertEquals(-2.5, SafeCalculator.evaluate("-5 / 2"), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCallsAndUnknownSyntax() {
        SafeCalculator.evaluate("sqrt(4)")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDivisionByZero() {
        SafeCalculator.evaluate("1 / (2 - 2)")
    }
}
