package dev.androml.core.security

import org.junit.Test

class SecretNameTest {
    @Test
    fun acceptsScopedLowercaseNames() {
        SecretName.requireValid("huggingface.read-token")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathTraversalNames() {
        SecretName.requireValid("../token")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUppercaseNames() {
        SecretName.requireValid("HF_TOKEN")
    }
}
