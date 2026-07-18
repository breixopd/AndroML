package dev.androml.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecretStoreTest {
    @Test
    fun roundTripsAndDeletesWithoutExposingStoredPlaintext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AndroidKeystoreSecretStore(
            context = context,
            keyAlias = "androml.test.secret.v1",
            preferencesName = "androml.test.encrypted-secrets",
        )

        store.write("test.token", "hf_test_secret_value")

        assertEquals("hf_test_secret_value", store.read("test.token"))
        val persistedValue = context
            .getSharedPreferences("androml.test.encrypted-secrets", Context.MODE_PRIVATE)
            .getString("test.token", null)
        check(persistedValue != null && !persistedValue.contains("hf_test_secret_value"))

        store.delete("test.token")
        assertNull(store.read("test.token"))
    }
}
