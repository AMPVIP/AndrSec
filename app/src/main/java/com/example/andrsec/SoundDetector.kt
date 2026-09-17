package com.example.andrsec
// SoundDetector.kt
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*

class SoundDetector(
    var thresholdDb: Double = 70.0,
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
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf
        )
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
                            if (now - lastTriggerTime > cooldownMs) {   // ← кулдаун
                                lastTriggerTime = now
                                println("SoundDetector: trigger db=$db")
                                withContext(Dispatchers.Main) { onSoundDetected(db) }
                            }
                        }
                    }
                }
            } finally {
                recorder.stop()
                recorder.release()
            }
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        job = null
    }
}