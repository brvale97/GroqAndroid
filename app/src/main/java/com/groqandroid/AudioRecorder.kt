package com.groqandroid

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Records audio from the microphone and saves it as a WAV file.
 * Format: 16kHz, mono, PCM 16-bit (optimal for Whisper).
 */
class AudioRecorder(private val cacheDir: File) {

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val MAX_DURATION_MS = 120_000L // 2 minutes max recording
    }

    @Volatile
    private var isRecording = false

    private var audioRecord: AudioRecord? = null
    private var recordingStartTime = 0L

    /** Callback invoked when max duration is reached. Called from IO thread. */
    var onMaxDurationReached: (() -> Unit)? = null

    val outputFile: File
        get() = File(cacheDir, "recording.wav")

    /** Duration of current/last recording in milliseconds. */
    val durationMs: Long
        get() = if (recordingStartTime > 0) System.currentTimeMillis() - recordingStartTime else 0

    /**
     * Records audio until [stop] is called or max duration is reached.
     * Must be called from a coroutine — runs on IO dispatcher.
     */
    suspend fun record() = withContext(Dispatchers.IO) {
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
            4096
        )

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        audioRecord = recorder
        val wavFile = outputFile
        val buffer = ByteArray(bufferSize)

        var totalBytes = 0L

        FileOutputStream(wavFile).use { fos ->
            // Write placeholder WAV header (44 bytes)
            fos.write(ByteArray(44))

            recorder.startRecording()
            isRecording = true
            recordingStartTime = System.currentTimeMillis()

            while (isRecording && isActive) {
                // Auto-stop at max duration
                if (System.currentTimeMillis() - recordingStartTime >= MAX_DURATION_MS) {
                    isRecording = false
                    onMaxDurationReached?.invoke()
                    break
                }
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    fos.write(buffer, 0, read)
                    totalBytes += read
                }
            }

            recorder.stop()
            recorder.release()
            audioRecord = null
        }

        // Write correct WAV header after fos is flushed and closed
        writeWavHeader(wavFile, totalBytes)
    }

    fun stop() {
        isRecording = false
    }

    /** Cleans up the WAV file after transcription. */
    fun cleanup() {
        try { outputFile.delete() } catch (_: Exception) {}
    }

    val recording: Boolean
        get() = isRecording

    private fun writeWavHeader(file: File, dataSize: Long) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            // RIFF header
            raf.writeBytes("RIFF")
            raf.writeIntLE((36 + dataSize).toInt())
            raf.writeBytes("WAVE")
            // fmt subchunk
            raf.writeBytes("fmt ")
            raf.writeIntLE(16) // subchunk size
            raf.writeShortLE(1) // PCM format
            raf.writeShortLE(channels)
            raf.writeIntLE(SAMPLE_RATE)
            raf.writeIntLE(byteRate)
            raf.writeShortLE(blockAlign)
            raf.writeShortLE(bitsPerSample)
            // data subchunk
            raf.writeBytes("data")
            raf.writeIntLE(dataSize.toInt())
        }
    }

    // Little-endian write helpers
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
