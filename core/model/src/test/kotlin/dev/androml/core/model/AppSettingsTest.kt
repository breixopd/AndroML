package dev.androml.core.model

import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun powerUserDefaultsKeepOptimizationAndThermalSafetyEnabled() {
        val defaults = AppSettings()
        assertTrue(defaults.expertMode)
        assertTrue(defaults.autoOptimize)
        assertTrue(defaults.thermalGuard)
    }
}
