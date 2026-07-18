package dev.androml.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

interface SecretStore {
    fun read(name: String): String?

    fun write(name: String, value: String)

    fun delete(name: String)
}

class SecretIntegrityException(cause: Throwable? = null) : GeneralSecurityException(
    "stored secret failed integrity validation",
    cause,
)

object SecretName {
    private val PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    fun requireValid(name: String) {
        require(name.matches(PATTERN)) {
            "secret name must be 1-64 lowercase safe characters"
        }
    }
}

/** Android Keystore AES-GCM storage for small app secrets such as API tokens. */
class AndroidKeystoreSecretStore(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : SecretStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    override fun read(name: String): String? {
        SecretName.requireValid(name)
        val encoded = preferences.getString(name, null) ?: return null
        return try {
            decrypt(name, encoded)
        } catch (error: AEADBadTagException) {
            throw SecretIntegrityException(error)
        } catch (error: GeneralSecurityException) {
            throw SecretIntegrityException(error)
        } catch (error: IllegalArgumentException) {
            throw SecretIntegrityException(error)
        }
    }

    override fun write(name: String, value: String) {
        SecretName.requireValid(name)
        require(value.isNotBlank()) { "secret value must not be blank" }
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_SECRET_BYTES) {
            "secret value exceeds the supported size"
        }
        check(preferences.edit().putString(name, encrypt(name, value)).commit()) {
            "secret could not be durably stored"
        }
    }

    override fun delete(name: String) {
        SecretName.requireValid(name)
        check(preferences.edit().remove(name).commit()) {
            "secret could not be removed"
        }
    }

    private fun encrypt(name: String, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(1 + cipher.iv.size + ciphertext.size)
            .put(FORMAT_VERSION)
            .put(cipher.iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(name: String, encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > 1 + GCM_IV_LENGTH_BYTES) { "secret payload is truncated" }
        require(payload[0] == FORMAT_VERSION) { "secret payload version is unsupported" }
        val iv = payload.copyOfRange(1, 1 + GCM_IV_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(1 + GCM_IV_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null)
        if (existing is SecretKey) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_KEY_ALIAS = "androml.secret.v1"
        const val DEFAULT_PREFERENCES_NAME = "androml.encrypted-secrets"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
        const val GCM_IV_LENGTH_BYTES = 12
        const val MAX_SECRET_BYTES = 16 * 1024
        const val FORMAT_VERSION: Byte = 1
    }
}
