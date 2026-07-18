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
        assertEquals(root.resolve("artifacts/$hash").canonicalFile, store.fileFor(hash).canonicalFile)
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

    @Test
    fun resumableArtifactSurvivesReopenAndCommitsAfterAppendingTheRest() {
        val root = temporaryRoot()
        val bytes = "resumable-model-bytes".toByteArray()
        val hash = sha256(bytes)
        val store = FileArtifactStore(root)

        store.beginResumable("job-1", hash, bytes.size.toLong()).use { partial ->
            partial.appendFrom(ByteArrayInputStream(bytes.copyOfRange(0, 9)))
            assertEquals(9L, partial.bytesWritten)
        }

        val reopened = store.beginResumable("job-1", hash, bytes.size.toLong())
        assertEquals(9L, reopened.bytesWritten)
        reopened.appendFrom(ByteArrayInputStream(bytes.copyOfRange(9, bytes.size)))
        assertEquals(bytes.size.toLong(), reopened.bytesWritten)
        val committed = reopened.commit()

        assertEquals(hash, committed.sha256)
        assertArrayEquals(bytes, store.open(hash).use { it.readBytes() })
        assertFalse(root.resolve("staging/job-1.partial").exists())
        deleteTree(root)
    }

    @Test
    fun resumableAppendOverflowLeavesTheExistingPartialUnchanged() {
        val root = temporaryRoot()
        val bytes = "partial".toByteArray()
        val store = FileArtifactStore(root)
        val partial = store.beginResumable("job-2", sha256(bytes), bytes.size.toLong())
        partial.appendFrom(ByteArrayInputStream(bytes.copyOfRange(0, 3)))

        var failure: ArtifactSizeException? = null
        try {
            partial.appendFrom(ByteArrayInputStream("too-long".toByteArray()))
        } catch (exception: ArtifactSizeException) {
            failure = exception
        }

        assertEquals(bytes.size.toLong() - 3L, failure?.maximumBytes)
        assertEquals(3L, partial.bytesWritten)
        deleteTree(root)
    }

    @Test
    fun resumableKeysAreValidatedBeforeCreatingPartialFiles() {
        val root = temporaryRoot()
        val store = FileArtifactStore(root)
        val hash = sha256("bytes".toByteArray())

        var failure: IllegalArgumentException? = null
        try {
            store.beginResumable("../outside", hash, 5L)
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
