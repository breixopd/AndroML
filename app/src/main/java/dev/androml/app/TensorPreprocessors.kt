package dev.androml.app

import android.graphics.Bitmap
import dev.androml.runtime.api.TensorDataType
import dev.androml.runtime.api.TensorInput
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Explicit, deterministic preprocessing for the Playground's generic tensor workloads. */
object TensorPreprocessors {
    private const val IMAGE_SIZE = 224
    private const val IMAGE_CHANNELS = 3
    private const val MAX_AUDIO_SECONDS = 60
    private const val TARGET_SAMPLE_RATE = 16_000

    /** Produces NHWC float32 RGB values in [0, 1] for common mobile vision models. */
    fun image(bitmap: Bitmap): TensorInput {
        val scaled = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        scaled.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
        val bytes = ByteBuffer
            .allocate(pixels.size * IMAGE_CHANNELS * TensorDataType.Float32.byteSize)
            .order(ByteOrder.nativeOrder())
        pixels.forEach { pixel ->
            bytes.putFloat(((pixel shr 16) and 0xff) / 255f)
            bytes.putFloat(((pixel shr 8) and 0xff) / 255f)
            bytes.putFloat((pixel and 0xff) / 255f)
        }
        if (scaled !== bitmap) scaled.recycle()
        return TensorInput(
            data = bytes.array(),
            shape = longArrayOf(1, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong(), IMAGE_CHANNELS.toLong()),
            dataType = TensorDataType.Float32,
        )
    }

    /** Reads bounded PCM16 WAV input and resamples mono audio to 16 kHz float32. */
    fun wav(input: InputStream): TensorInput {
        val bytes = input.use { stream -> readBounded(stream, MAX_AUDIO_SECONDS * 32000 * 2 + 64) }
        require(bytes.size >= 44) { "WAV file is too small" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.readAscii(0, 4) == "RIFF" && buffer.readAscii(8, 4) == "WAVE") {
            "only RIFF/WAVE audio is supported"
        }
        var offset = 12
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataLength = 0
        while (offset + 8 <= bytes.size) {
            val chunkId = buffer.readAscii(offset, 4)
            val chunkLength = buffer.getInt(offset + 4)
            require(chunkLength >= 0 && chunkLength <= bytes.size - offset - 8) { "WAV chunk is invalid" }
            when (chunkId) {
                "fmt " -> {
                    require(chunkLength >= 16) { "WAV fmt chunk is invalid" }
                    val format = buffer.getShort(offset + 8).toInt() and 0xffff
                    channels = buffer.getShort(offset + 10).toInt() and 0xffff
                    sampleRate = buffer.getInt(offset + 12)
                    bitsPerSample = buffer.getShort(offset + 22).toInt() and 0xffff
                    require(format == 1 && channels in 1..2 && sampleRate in 8_000..96_000 && bitsPerSample == 16) {
                        "WAV must be PCM16 mono or stereo"
                    }
                }
                "data" -> {
                    dataOffset = offset + 8
                    dataLength = chunkLength
                    break
                }
            }
            offset += 8 + chunkLength + (chunkLength and 1)
        }
        require(channels > 0 && dataOffset >= 0 && dataLength > 0) { "WAV fmt/data chunks are required" }
        val frameBytes = channels * 2
        val frameCount = (dataLength / frameBytes).coerceAtMost(sampleRate * MAX_AUDIO_SECONDS)
        val mono = FloatArray(frameCount)
        repeat(frameCount) { frame ->
            var total = 0f
            repeat(channels) { channel ->
                val sampleOffset = dataOffset + frame * frameBytes + channel * 2
                total += buffer.getShort(sampleOffset).toFloat() / 32768f
            }
            mono[frame] = total / channels
        }
        val resampled = resample(mono, sampleRate, TARGET_SAMPLE_RATE)
        val output = ByteBuffer.allocate(resampled.size * TensorDataType.Float32.byteSize)
            .order(ByteOrder.nativeOrder())
        resampled.forEach(output::putFloat)
        return TensorInput(
            data = output.array(),
            shape = longArrayOf(1, resampled.size.toLong()),
            dataType = TensorDataType.Float32,
        )
    }

    private fun resample(input: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
        if (sourceRate == targetRate) return input
        val outputSize = (input.size.toLong() * targetRate / sourceRate)
            .coerceAtMost((TARGET_SAMPLE_RATE * MAX_AUDIO_SECONDS).toLong())
            .toInt()
            .coerceAtLeast(1)
        return FloatArray(outputSize) { index ->
            val sourcePosition = index.toDouble() * sourceRate / targetRate
            val left = sourcePosition.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = sourcePosition - left
            (input[left] * (1.0 - fraction) + input[right] * fraction).toFloat()
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "audio input exceeds the safety limit" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun ByteBuffer.readAscii(offset: Int, length: Int): String {
        val bytes = ByteArray(length)
        val view = duplicate()
        view.position(offset)
        view.get(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }
}
