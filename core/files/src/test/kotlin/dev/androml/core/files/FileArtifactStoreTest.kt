package dev.androml.core.files

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileArtifactStoreTest {
    @Test
    fun commitPromotesVerifiedBytesToContentAddressedPath() {
        val root = temporaryRoot()
        val bytes = "androml-test-artifact".toByteArray()
        val hash = sha256(bytes)
        val store = FileArtifactStore(root)

        val staged = store.stage(hash, bytes.size.toLong())
        staged.copyFrom(ByteArrayInputStream(bytes))
        val reference = staged.commit()

        assertEquals(hash, reference.sha256)
        assertEquals(bytes.size.toLong(), reference.sizeBytes)
        assertTrue(store.contains(hash))
        assertArrayEquals(bytes, store.open(hash).use { it.readBytes() })
        assertTrue(root.resolve("artifacts/$hash").isFile)
        assertTrue(root.resolve("staging").listFiles().orEmpty().isEmpty())
        deleteTree(root)
    }

    @Test
    fun commitQuarantinesBytesWhenHashDoesNotMatch() {
        val root = temporaryRoot()
        val expectedHash = sha256("expected".toByteArray())
        val store = FileArtifactStore(root)
        val staged = store.stage(expectedHash)
        staged.copyFrom(ByteArrayInputStream("tampered".toByteArray()))

        var failure: ArtifactIntegrityException? = null
        try {
            staged.commit()
        } catch (exception: ArtifactIntegrityException) {
            failure = exception
        }

        assertEquals(expectedHash, failure?.expectedSha256)
        assertFalse(store.contains(expectedHash))
        assertTrue(root.resolve("quarantine").listFiles().orEmpty().isNotEmpty())
        deleteTree(root)
    }

    @Test
    fun copyFromRejectsBytesOverTheDeclaredSize() {
        val root = temporaryRoot()
        val bytes = "too-large".toByteArray()
        val store = FileArtifactStore(root)
        val staged = store.stage(sha256(bytes), expectedSizeBytes = 2)

        var failure: ArtifactSizeException? = null
        try {
            staged.copyFrom(ByteArrayInputStream(bytes))
        } catch (exception: ArtifactSizeException) {
            failure = exception
        }

        assertEquals(2L, failure?.maximumBytes)
        assertFalse(staged.isComplete)
        deleteTree(root)
    }

    @Test
    fun invalidHashesAreRejectedBeforeAnyFileIsCreated() {
        val root = temporaryRoot()
        val store = FileArtifactStore(root)

        var failure: IllegalArgumentException? = null
        try {
            store.stage("../../outside")
        } catch (exception: IllegalArgumentException) {
            failure = exception
        }

        assertTrue(failure != null)
        assertTrue(root.listFiles().orEmpty().isEmpty())
        deleteTree(root)
    }

    private fun temporaryRoot(): File = Files.createTempDirectory("androml-artifacts-").toFile()

    private fun deleteTree(root: File) {
        root.walkBottomUp().forEach(File::delete)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
