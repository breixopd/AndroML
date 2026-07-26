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
class FileArtifactStore(
    private val root: File,
    private val maxArtifactBytes: Long = DEFAULT_MAX_BYTES,
    private val freeSpaceReserveBytes: Long = 256L * 1024L * 1024L,
    private val stagingQuotaBytes: Long = 20L * 1024L * 1024L * 1024L,
    private val committedQuotaBytes: Long = 32L * 1024L * 1024L * 1024L,
) {
    private val writeReservations = mutableMapOf<File, Long>()

    init {
        require(
            maxArtifactBytes > 0 &&
                freeSpaceReserveBytes >= 0 &&
                stagingQuotaBytes > 0 &&
                committedQuotaBytes >= maxArtifactBytes,
        )
    }

    /** Removes abandoned resumable files and bounds disk consumed by failed downloads. */
    fun cleanupStaging(maxAgeMillis: Long = 24L * 60L * 60L * 1000L): Long =
        cleanupDirectory(File(root, STAGING_DIRECTORY), maxAgeMillis, Long.MAX_VALUE)

    /** Removes old quarantined failures, retaining only recent evidence. */
    fun cleanupQuarantine(maxAgeMillis: Long = 7L * 24L * 60L * 60L * 1000L): Long =
        cleanupDirectory(File(root, QUARANTINE_DIRECTORY), maxAgeMillis, 256L * 1024L * 1024L)

    private fun cleanupDirectory(directory: File, maxAgeMillis: Long, quotaBytes: Long): Long {
        require(maxAgeMillis >= 0)
        if (!directory.isDirectory) return 0
        val now = System.currentTimeMillis()
        val files = directory.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        var removed = 0L
        files.forEach { file ->
            if (now - file.lastModified() >= maxAgeMillis || total > quotaBytes) {
                val size = file.length(); if (file.delete()) { total -= size; removed += size }
            }
        }
        return removed
    }
    @Synchronized
    fun stage(expectedSha256: String, expectedSizeBytes: Long? = null): StagedArtifact {
        require(isSha256(expectedSha256)) { "expectedSha256 must be 64 lowercase hexadecimal characters" }
        require(expectedSizeBytes == null || expectedSizeBytes >= 0) {
            "expectedSizeBytes must be non-negative"
        }
        expectedSizeBytes?.let { checkCapacity(it, excludedStagingFile = null) }

        val stagingDirectory = File(root, STAGING_DIRECTORY).apply { mkdirs() }
        val temporaryFile = File(stagingDirectory, "${UUID.randomUUID()}.partial")
        return StagedArtifact(this, temporaryFile, expectedSha256, expectedSizeBytes)
    }

    /**
     * Opens a durable, job-keyed partial artifact. The partial is not visible
     * through [contains] or [open] until [ResumableArtifact.commit] succeeds.
     */
    @Synchronized
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
        checkCapacity(expectedSizeBytes - partialFile.length(), excludedStagingFile = null)
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

    /** Returns a verified, immutable artifact location for APIs that must transfer a read-only FD. */
    fun fileFor(sha256: String): File {
        require(isSha256(sha256)) { "sha256 must be 64 lowercase hexadecimal characters" }
        val file = artifactFile(sha256)
        check(file.isFile) { "artifact not found: $sha256" }
        return file
    }

    @Synchronized
    internal fun commit(staged: StagedArtifact): StoredArtifact {
        check(staged.isComplete) { "staged artifact must be completely written before commit" }

        val result = commitFile(staged.file, staged.expectedSha256, staged.expectedSizeBytes)
        staged.markCommitted()
        return result
    }

    @Synchronized
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
        if (actualSize > maxArtifactBytes) {
            quarantine(file, "maximum-size")
            throw ArtifactSizeException(maxArtifactBytes, actualSize)
        }
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
            val committedBytes = destination.parentFile
                ?.listFiles()
                .orEmpty()
                .filter { it.isFile }
                .sumOf { it.length() }
            if (actualSize > committedQuotaBytes - committedBytes) {
                throw IOException("committed artifact quota exceeded")
            }
            atomicMove(file, destination)
        }

        return StoredArtifact(expectedSha256, actualSize)
    }

    @Synchronized
    internal fun discard(file: File) {
        writeReservations.remove(file)
        file.delete()
    }

    private fun artifactFile(sha256: String): File = File(File(root, ARTIFACT_DIRECTORY), sha256)

    @Synchronized
    private fun checkCapacity(size: Long, excludedStagingFile: File?) {
        require(size <= maxArtifactBytes) { "artifact exceeds the maximum permitted size" }
        root.mkdirs()
        if (size > root.usableSpace - freeSpaceReserveBytes) {
            throw IOException("insufficient free space for artifact and safety reserve")
        }
        val staging = File(root, STAGING_DIRECTORY)
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it != excludedStagingFile }
            .sumOf { it.length() }
        if (size > stagingQuotaBytes - staging) throw IOException("staging quota exceeded")
    }

    @Synchronized
    private fun reserveWrite(file: File, size: Long) {
        require(size >= 0L)
        val current = writeReservations[file] ?: 0L
        if (size <= current) return
        val additional = size - current
        root.mkdirs()
        val alreadyReserved = writeReservations.values.sum()
        if (additional > root.usableSpace - freeSpaceReserveBytes - alreadyReserved) {
            throw IOException("insufficient free space for artifact and safety reserve")
        }
        val staging = File(root, STAGING_DIRECTORY)
            .listFiles()
            .orEmpty()
            .filter { it.isFile }
            .sumOf { it.length() }
        if (additional > stagingQuotaBytes - staging - alreadyReserved) {
            throw IOException("staging quota exceeded")
        }
        writeReservations[file] = size
    }

    @Synchronized
    private fun writeReserved(file: File, size: Int, write: () -> Unit) {
        require(size >= 0)
        val current = writeReservations[file] ?: 0L
        if (current < size) {
            reserveWrite(file, maxOf(UNKNOWN_WRITE_RESERVATION_BYTES, size.toLong()))
        }
        write()
        val remaining = requireNotNull(writeReservations[file]) - size
        if (remaining == 0L) writeReservations.remove(file) else writeReservations[file] = remaining
    }

    @Synchronized
    private fun releaseWriteReservation(file: File) {
        writeReservations.remove(file)
    }

    private fun quarantine(file: File, reason: String) {
        cleanupQuarantine()
        val quarantineDirectory = File(root, QUARANTINE_DIRECTORY).apply { mkdirs() }
        val destination = File(quarantineDirectory, "${file.name}.$reason.${System.currentTimeMillis()}")
        atomicMove(file, destination)
        cleanupQuarantine()
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
        private const val UNKNOWN_WRITE_RESERVATION_BYTES = 64L * 1024L * 1024L

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

        fun copyFrom(input: InputStream, maxBytes: Long = expectedSizeBytes ?: store.maxArtifactBytes) {
            check(!isComplete) { "staged artifact has already been completed" }
            require(maxBytes >= 0) { "maxBytes must be non-negative" }
            require(maxBytes <= store.maxArtifactBytes) { "maxBytes exceeds the artifact safety limit" }

            try {
                store.reserveWrite(
                    file,
                    expectedSizeBytes ?: minOf(maxBytes, UNKNOWN_WRITE_RESERVATION_BYTES),
                )
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
                        store.writeReserved(file, read) { output.write(buffer, 0, read) }
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
            } finally {
                store.releaseWriteReservation(file)
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
            store.reserveWrite(file, maxBytes)
            try {
                file.parentFile?.mkdirs()
                RandomAccessFile(file, "rw").use { randomAccess ->
                    randomAccess.seek(initialSize)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var appended = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (read.toLong() > maxBytes - appended) {
                            randomAccess.setLength(initialSize)
                            randomAccess.fd.sync()
                            throw ArtifactSizeException(maxBytes, appended + read)
                        }
                        store.writeReserved(file, read) { randomAccess.write(buffer, 0, read) }
                        appended += read
                        onBytesWritten(initialSize + appended)
                    }
                    randomAccess.fd.sync()
                }
            } finally {
                store.releaseWriteReservation(file)
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
            if (!isCommitted) store.discard(file)
        }

        internal fun markCommitted() {
            isCommitted = true
        }

        override fun close() = Unit
    }
}
