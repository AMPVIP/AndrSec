package com.example.andrsec

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*

class SoundDetector(
    @Volatile var thresholdDb: Double = 70.0,
    private val cooldownMs: Long = 10_000,
    private val onSoundDetected: (Double) -> Unit
) {
    @Volatile
    var isRunning: Boolean = false
        private set
    private var job: Job? = null

    private var lastTriggerTime = 0L
    private val sampleRate = 44100

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            println("SoundDetector: getMinBufferSize failed ($minBuf)")
            isRunning = false
            return
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            println("SoundDetector: AudioRecord not initialized")
            recorder.release()
            isRunning = false
            return
        }

        val buffer = ShortArray(minBuf)

        job = scope.launch(Dispatchers.IO) {
            recorder.startRecording()
            try {
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            val s = buffer[i] / 32768.0
                            sum += s * s
                        }
                        val rms = kotlin.math.sqrt(sum / read)
                        val db = 20 * kotlin.math.log10(rms.coerceAtLeast(1e-9)) + 94
                        if (db > thresholdDb) {
                            val now = System.currentTimeMillis()
                            if (now - lastTriggerTime > cooldownMs) {
                                lastTriggerTime = now
                                println("SoundDetector: trigger db=$db")
                                withContext(Dispatchers.Main) { onSoundDetected(db) }
                            }
                        }
                    }
                }
            } finally {
                try { recorder.stop() } catch (_: Exception) {}
                recorder.release()
            }
        }
    }

    fun stop() {
        isRunning = false
        val oldJob = job
        job = null
        if (oldJob != null) {
            // ⚠️ Ждём освобождения AudioRecord, иначе следующий start() упадёт
            runBlocking { oldJob.cancelAndJoin() }
        }
    }
}