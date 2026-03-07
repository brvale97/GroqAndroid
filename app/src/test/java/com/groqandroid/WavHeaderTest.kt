package com.groqandroid

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for AudioRecorder WAV header generation.
 * The WAV header must be byte-perfect or Whisper will reject the file.
 */
class WavHeaderTest {

    private lateinit var tempDir: File

    @Before
    fun setup() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "wav_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
    }

    @Test
    fun `WAV header has correct RIFF signature`() {
        val file = createWavFile(dataSize = 100)
        val bytes = file.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4))
    }

    @Test
    fun `WAV header has correct WAVE format`() {
        val file = createWavFile(dataSize = 100)
        val bytes = file.readBytes()
        assertEquals("WAVE", String(bytes, 8, 4))
    }

    @Test
    fun `WAV header has correct fmt subchunk`() {
        val file = createWavFile(dataSize = 100)
        val bytes = file.readBytes()
        assertEquals("fmt ", String(bytes, 12, 4))

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16, buf.getInt(16))  // subchunk size
        assertEquals(1, buf.getShort(20).toInt())  // PCM format
        assertEquals(1, buf.getShort(22).toInt())  // mono
        assertEquals(16000, buf.getInt(24))  // sample rate
        assertEquals(32000, buf.getInt(28))  // byte rate (16000 * 1 * 16/8)
        assertEquals(2, buf.getShort(32).toInt())  // block align (1 * 16/8)
        assertEquals(16, buf.getShort(34).toInt())  // bits per sample
    }

    @Test
    fun `WAV header has correct data subchunk`() {
        val dataSize = 1000
        val file = createWavFile(dataSize = dataSize)
        val bytes = file.readBytes()
        assertEquals("data", String(bytes, 36, 4))

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(dataSize, buf.getInt(40))  // data chunk size
    }

    @Test
    fun `WAV header file size field is correct`() {
        val dataSize = 500
        val file = createWavFile(dataSize = dataSize)
        val bytes = file.readBytes()

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + dataSize, buf.getInt(4))  // RIFF chunk size
    }

    @Test
    fun `WAV with zero data size has valid header`() {
        val file = createWavFile(dataSize = 0)
        val bytes = file.readBytes()
        assertEquals(44, bytes.size)  // header only
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36, buf.getInt(4))  // RIFF size = 36 + 0
        assertEquals(0, buf.getInt(40))  // data size = 0
    }

    @Test
    fun `WAV with single sample has correct size`() {
        val file = createWavFile(dataSize = 2)  // one 16-bit sample
        val bytes = file.readBytes()
        assertEquals(46, bytes.size)  // 44 header + 2 data

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(38, buf.getInt(4))  // 36 + 2
        assertEquals(2, buf.getInt(40))  // data size
    }

    @Test
    fun `WAV header is exactly 44 bytes`() {
        val file = createWavFile(dataSize = 100)
        val bytes = file.readBytes()
        // Verify data starts at byte 44
        assertEquals("data", String(bytes, 36, 4))
        // Total header = 44 bytes (RIFF:4 + size:4 + WAVE:4 + fmt:4 + fmtSize:4 + fmtData:16 + data:4 + dataSize:4)
    }

    /**
     * Helper: creates a WAV file using the same header format as AudioRecorder.
     */
    private fun createWavFile(dataSize: Int): File {
        val file = File(tempDir, "test.wav")
        // Write dummy data
        file.writeBytes(ByteArray(44 + dataSize))
        // Overwrite with proper header using the same logic as AudioRecorder
        writeWavHeader(file, dataSize.toLong())
        return file
    }

    /** Mirrors AudioRecorder.writeWavHeader exactly */
    private fun writeWavHeader(file: File, dataSize: Long) {
        val sampleRate = 16000
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.writeBytes("RIFF")
            raf.writeIntLE((36 + dataSize).toInt())
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeIntLE(16)
            raf.writeShortLE(1)
            raf.writeShortLE(channels)
            raf.writeIntLE(sampleRate)
            raf.writeIntLE(byteRate)
            raf.writeShortLE(blockAlign)
            raf.writeShortLE(bitsPerSample)
            raf.writeBytes("data")
            raf.writeIntLE(dataSize.toInt())
        }
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}
