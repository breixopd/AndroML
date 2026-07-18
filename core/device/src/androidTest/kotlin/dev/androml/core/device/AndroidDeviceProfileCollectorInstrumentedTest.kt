package dev.androml.core.device

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDeviceProfileCollectorInstrumentedTest {
    @Test
    fun collectsFactsFromTheAttachedAndroidDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = AndroidDeviceProfileCollector(context).collect()

        assertEquals(Build.VERSION.SDK_INT, profile.androidApi)
        assertTrue(profile.deviceName.isNotBlank())
        assertTrue(profile.cpuCoreCount > 0)
        assertTrue(profile.supportedAbis.isNotEmpty())
        assertTrue((profile.availableStorageBytes ?: 0) > 0)
    }
}

