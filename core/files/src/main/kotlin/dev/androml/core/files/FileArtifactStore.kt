package dev.androml.core.files

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class StoredArtifact(
    val sha256: String,
    val sizeBytes: Long,
)

class ArtifactIntegrityException(
    val expectedSha256: String,
    val actualSha256: String,
) : IOException("artifact hash mismatch: expected $expectedSha256, got $actualSha256")

class ArtifactSizeException(
    val maximumBytes: Long,
    val actualBytes: Long,
) : IOException("artifact exceeds ${maximumBytes}B limit: got ${actualBytes}B")

/**
 * App-private, content-addressed storage for model and document artifacts.
 *
 * A caller must stage bytes, complete the hash/size checks, and explicitly commit them. A staged
 * file is never exposed through [open] and is promoted with an atomic move when the platform
 * supports it.
 */
class FileArtifactStore(private val root: File) {
    fun stage(expectedSha256: String, expectedSizeBytes: Long? = null): StagedArtifact {
        require(isSha256(expectedSha256)) { "expectedSha256 must be 64 lowercase hexadecimal characters" }
        require(expectedSizeBytes == null || expectedSizeBytes >= 0) {
            "expectedSizeBytes must be non-negative"
        }

        val stagingDirectory = File(root, STAGING_DIRECTORY).apply { mkdirs() }
        val temporaryFile = File(stagingDirectory, "${UUID.randomUUID()}.partial")
        return StagedArtifact(this, temporaryFile, expectedSha256, expectedSizeBytes)
    }

    fun contains(sha256: String): Boolean {
        require(isSha256(sha256)) { "sha256 must be 64 lowercase hexadecimal characters" }
        return artifactFile(sha256).isFile
    }

    fun open(sha256: String): InputStream {
        require(isSha256(sha256)) { "sha256 must be 64 lowercase hexadecimal characters" }
        val file = artifactFile(sha256)
        if (!file.isFile) {
            throw FileNotFoundException("artifact not found: $sha256")
        }
        return FileInputStream(file)
    }

    internal fun commit(staged: StagedArtifact): StoredArtifact {
        check(staged.isComplete) { "staged artifact must be completely written before commit" }

        val actualSize = staged.file.length()
        val expectedSize = staged.expectedSizeBytes
        if (expectedSize != null && actualSize != expectedSize) {
            quarantine(staged.file, "size")
            throw ArtifactSizeException(expectedSize, actualSize)
        }

        val actualSha256 = sha256(staged.file)
        if (actualSha256 != staged.expectedSha256) {
            quarantine(staged.file, "hash")
            throw ArtifactIntegrityException(staged.expectedSha256, actualSha256)
        }

        val destination = artifactFile(staged.expectedSha256)
        destination.parentFile?.mkdirs()
        if (destination.isFile) {
            val existingHash = sha256(destination)
            check(existingHash == staged.expectedSha256) {
                "existing artifact has an unexpected hash: ${staged.expectedSha256}"
            }
            staged.file.delete()
        } else {
            atomicMove(staged.file, destination)
        }

        staged.markCommitted()
        return StoredArtifact(staged.expectedSha256, actualSize)
    }

    internal fun discard(file: File) {
        file.delete()
    }

    private fun artifactFile(sha256: String): File = File(File(root, ARTIFACT_DIRECTORY), sha256)

    private fun quarantine(file: File, reason: String) {
        val quarantineDirectory = File(root, QUARANTINE_DIRECTORY).apply { mkdirs() }
        val destination = File(quarantineDirectory, "${file.name}.$reason")
        atomicMove(file, destination)
    }

    private fun atomicMove(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.toHex()
    }

    companion object {
        private const val ARTIFACT_DIRECTORY = "artifacts"
        private const val QUARANTINE_DIRECTORY = "quarantine"
        private const val STAGING_DIRECTORY = "staging"
        private const val BUFFER_SIZE = 64 * 1024
        private const val DEFAULT_MAX_BYTES = 16L * 1024L * 1024L * 1024L

        private fun isSha256(value: String): Boolean =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

        private fun MessageDigest.toHex(): String = digest()
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    class StagedArtifact internal constructor(
        private val store: FileArtifactStore,
        internal val file: File,
        internal val expectedSha256: String,
        internal val expectedSizeBytes: Long?,
    ) {
        var isComplete: Boolean = false
            private set

        fun copyFrom(input: InputStream, maxBytes: Long = expectedSizeBytes ?: DEFAULT_MAX_BYTES) {
            check(!isComplete) { "staged artifact has already been completed" }
            require(maxBytes >= 0) { "maxBytes must be non-negative" }

            try {
                file.parentFile?.mkdirs()
                file.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read.toLong() > maxBytes - total) {
                            throw ArtifactSizeException(maxBytes, total + read)
                        }
                        output.write(buffer, 0, read)
                        total += read
                    }
                    output.flush()
                    output.channel.force(true)
                }
                isComplete = true
            } catch (exception: ArtifactSizeException) {
                store.discard(file)
                isComplete = false
                throw exception
            } catch (exception: IOException) {
                store.discard(file)
                isComplete = false
                throw exception
            }
        }

        fun commit(): StoredArtifact = store.commit(this)

        fun discard() {
            store.discard(file)
            isComplete = false
        }

        internal fun markCommitted() {
            isComplete = false
        }
    }
}
