package com.example.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "AudioEngine"
        const val MIC_SAMPLE_RATE = 16000
        const val SPEAKER_SAMPLE_RATE = 24000
        const val CHUNK_SIZE = 1024
    }

    var onAudioChunkReady: ((String) -> Unit)? = null
    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingStopped: (() -> Unit)? = null
    var onAmplitudeChanged: ((Float) -> Unit)? = null

    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)
    val isSpeaking = AtomicBoolean(false)
    val isInCallMode = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val playbackQueue = LinkedBlockingQueue<ByteArray>()

    private var recordThread: Thread? = null
    private var playThread: Thread? = null

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SPEAKER_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufferSize, 8192)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SPEAKER_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AudioTrack: ${e.message}", e)
        }
    }

    fun startRecording() {
        if (isRecording.get()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot start recording: RECORD_AUDIO permission missing")
            return
        }

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufferSize, CHUNK_SIZE * 4)

            var record: AudioRecord? = null
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MIC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            } catch (e: Exception) {
                Log.w(TAG, "VOICE_RECOGNITION source unavailable, falling back to MIC")
            }

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    MIC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                return
            }

            audioRecord = record
            record.startRecording()
            isRecording.set(true)

            recordThread = Thread({
                val buffer = ByteArray(CHUNK_SIZE)
                while (isRecording.get()) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readBytes > 0) {
                        val rms = calculateRms(buffer, readBytes)
                        onAmplitudeChanged?.invoke(rms)

                        // Suppress mic when MYRA is speaking or muted or in-call mode
                        if (!isMuted.get() && !isSpeaking.get() && !isInCallMode.get()) {
                            val chunkToSend = if (readBytes == buffer.size) buffer else buffer.copyOf(readBytes)
                            val base64 = Base64.encodeToString(chunkToSend, Base64.NO_WRAP)
                            onAudioChunkReady?.invoke(base64)
                        }
                    }
                }
            }, "Myra-Mic-Record-Thread").apply { start() }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord: ${e.message}", e)
        }
    }

    fun stopRecording() {
        isRecording.set(false)
        try {
            recordThread?.interrupt()
            recordThread = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ${e.message}")
        }
    }

    fun startPlayback() {
        if (isPlaying.get()) return
        isPlaying.set(true)

        try {
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioTrack playback: ${e.message}")
        }

        playThread = Thread({
            while (isPlaying.get()) {
                try {
                    val audioData = playbackQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (audioData != null && audioData.isNotEmpty()) {
                        if (!isSpeaking.get()) {
                            isSpeaking.set(true)
                            onSpeakingStarted?.invoke()
                        }
                        audioTrack?.write(audioData, 0, audioData.size)
                        val rms = calculateRms(audioData, audioData.size)
                        onAmplitudeChanged?.invoke(rms)
                    } else {
                        if (playbackQueue.isEmpty() && isSpeaking.get()) {
                            isSpeaking.set(false)
                            onSpeakingStopped?.invoke()
                            onAmplitudeChanged?.invoke(0f)
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Playback error: ${e.message}")
                }
            }
        }, "Myra-AudioTrack-Play-Thread").apply { start() }
    }

    fun stopPlayback() {
        isPlaying.set(false)
        try {
            playThread?.interrupt()
            playThread = null
            playbackQueue.clear()
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping playback: ${e.message}")
        }
    }

    fun queueAudio(pcmData: ByteArray) {
        if (pcmData.isNotEmpty()) {
            playbackQueue.offer(pcmData)
        }
    }

    fun interruptPlayback() {
        playbackQueue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error interrupting playback: ${e.message}")
        }
        if (isSpeaking.get()) {
            isSpeaking.set(false)
            onSpeakingStopped?.invoke()
            onAmplitudeChanged?.invoke(0f)
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
    }

    fun isMuted(): Boolean = isMuted.get()

    fun release() {
        stopRecording()
        stopPlayback()
        try {
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio resources: ${e.message}")
        }
    }

    private fun calculateRms(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        val sampleCount = length / 2
        if (sampleCount == 0) return 0f

        for (i in 0 until length - 1 step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val shortSample = sample.toShort()
            sum += (shortSample * shortSample).toDouble()
        }
        val rms = sqrt(sum / sampleCount)
        // Normalize against max short 32767
        val normalized = (rms / 32767.0).toFloat()
        return normalized.coerceIn(0f, 1f)
    }
}
