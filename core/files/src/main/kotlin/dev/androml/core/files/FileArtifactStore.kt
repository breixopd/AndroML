package dev.androml.core.files

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
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

    /**
     * Opens a durable, job-keyed partial artifact. The partial is not visible
     * through [contains] or [open] until [ResumableArtifact.commit] succeeds.
     */
    fun beginResumable(
        key: String,
        expectedSha256: String,
        expectedSizeBytes: Long,
    ): ResumableArtifact {
        require(isResumableKey(key)) { "key must contain only safe filename characters" }
        require(isSha256(expectedSha256)) {
            "expectedSha256 must be 64 lowercase hexadecimal characters"
        }
        require(expectedSizeBytes >= 0) { "expectedSizeBytes must be non-negative" }

        val stagingDirectory = File(root, STAGING_DIRECTORY).apply { mkdirs() }
        val partialFile = File(stagingDirectory, "$key.partial")
        if (partialFile.isFile && partialFile.length() > expectedSizeBytes) {
            val actualSize = partialFile.length()
            quarantine(partialFile, "partial-size")
            throw ArtifactSizeException(expectedSizeBytes, actualSize)
        }
        return ResumableArtifact(this, partialFile, expectedSha256, expectedSizeBytes)
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

        val result = commitFile(staged.file, staged.expectedSha256, staged.expectedSizeBytes)
        staged.markCommitted()
        return result
    }

    internal fun commitResumable(staged: ResumableArtifact): StoredArtifact {
        check(!staged.isCommitted) { "resumable artifact has already been committed" }

        val result = commitFile(staged.file, staged.expectedSha256, staged.expectedSizeBytes)
        staged.markCommitted()
        return result
    }

    private fun commitFile(
        file: File,
        expectedSha256: String,
        expectedSizeBytes: Long?,
    ): StoredArtifact {
        check(file.isFile) { "staged artifact file does not exist" }

        val actualSize = file.length()
        val expectedSize = expectedSizeBytes
        if (expectedSize != null && actualSize != expectedSize) {
            quarantine(file, "size")
            throw ArtifactSizeException(expectedSize, actualSize)
        }

        val actualSha256 = sha256(file)
        if (actualSha256 != expectedSha256) {
            quarantine(file, "hash")
            throw ArtifactIntegrityException(expectedSha256, actualSha256)
        }

        val destination = artifactFile(expectedSha256)
        destination.parentFile?.mkdirs()
        if (destination.isFile) {
            val existingHash = sha256(destination)
            check(existingHash == expectedSha256) {
                "existing artifact has an unexpected hash: $expectedSha256"
            }
            file.delete()
        } else {
            atomicMove(file, destination)
        }

        return StoredArtifact(expectedSha256, actualSize)
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

        private fun isResumableKey(value: String): Boolean =
            value.length in 1..128 &&
                value.first().let { it.isLetterOrDigit() } &&
                value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

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

    /** A durable partial artifact used by resumable network transfers. */
    class ResumableArtifact internal constructor(
        private val store: FileArtifactStore,
        internal val file: File,
        internal val expectedSha256: String,
        internal val expectedSizeBytes: Long,
    ) : AutoCloseable {
        var isCommitted: Boolean = false
            private set

        val bytesWritten: Long
            get() = if (file.isFile) file.length() else 0L

        /** Appends bytes and leaves the durable partial intact if the input fails. */
        fun appendFrom(
            input: InputStream,
            maxBytes: Long = expectedSizeBytes - bytesWritten,
            onBytesWritten: (Long) -> Unit = {},
        ) {
            check(!isCommitted) { "resumable artifact has already been committed" }
            require(maxBytes >= 0) { "maxBytes must be non-negative" }
            require(bytesWritten <= expectedSizeBytes) {
                "resumable artifact already exceeds the expected size"
            }
            require(maxBytes <= expectedSizeBytes - bytesWritten) {
                "maxBytes must not exceed the remaining expected size"
            }

            val initialSize = bytesWritten
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { randomAccess ->
                randomAccess.seek(initialSize)
                val buffer = ByteArray(BUFFER_SIZE)
                var appended = 0L
                try {
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (read.toLong() > maxBytes - appended) {
                            randomAccess.setLength(initialSize)
                            randomAccess.fd.sync()
                            throw ArtifactSizeException(maxBytes, appended + read)
                        }
                        randomAccess.write(buffer, 0, read)
                        appended += read
                        onBytesWritten(initialSize + appended)
                    }
                    randomAccess.fd.sync()
                } catch (exception: ArtifactSizeException) {
                    throw exception
                } catch (exception: IOException) {
                    throw exception
                }
            }
        }

        fun reset() {
            check(!isCommitted) { "resumable artifact has already been committed" }
            if (!file.isFile) return
            RandomAccessFile(file, "rw").use { randomAccess ->
                randomAccess.setLength(0L)
                randomAccess.fd.sync()
            }
        }

        fun commit(): StoredArtifact = store.commitResumable(this)

        fun discard() {
            if (!isCommitted) file.delete()
        }

        internal fun markCommitted() {
            isCommitted = true
        }

        override fun close() = Unit
    }
}
