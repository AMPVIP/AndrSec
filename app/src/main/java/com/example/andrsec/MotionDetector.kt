package com.example.andrsec

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class MotionDetector(
    var sensitivity: Int = 5,
    private val cooldownMs: Int = 10_000,
    private val frameSkip: Int = 1,
    private val onMotion: () -> Unit
) : ImageAnalysis.Analyzer {

    private var grayBuffer: ByteArray? = null
    private var prevGray: ByteArray? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var frameCount = 0L
    private var lastTrigger = 0L
    private var initialized = false

    override fun analyze(image: ImageProxy) {
        val w = image.width
        val h = image.height

        if (frameCount++ % frameSkip != 0L) {
            image.close()
            return
        }

        // Пересоздаём буферы только при смене разрешения
        if (grayBuffer == null || lastWidth != w || lastHeight != h) {
            grayBuffer = ByteArray(w * h)
            prevGray = ByteArray(w * h)
            lastWidth = w
            lastHeight = h
            initialized = false
            println("MotionDetector: буферы пересозданы ${w}x$h")
        }

        val gray = grayBuffer!!
        val prev = prevGray!!

        // ⚠️ Копируем Y-плоскость построчно, учитывая rowStride
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        try {
            if (pixelStride == 1) {
                // Быстрый путь: построчно
                for (row in 0 until h) {
                    buffer.position(row * rowStride)
                    val toRead = minOf(w, buffer.remaining())
                    if (toRead > 0) buffer.get(gray, row * w, toRead)
                }
            } else {
                // Медленный путь: сэмплируем с pixelStride
                for (row in 0 until h) {
                    for (col in 0 until w) {
                        val idx = row * rowStride + col * pixelStride
                        if (idx < buffer.limit()) {
                            gray[row * w + col] = buffer.get(idx)
                        }
                    }
                }
            }
        } finally {
            image.close()
        }

        // Первый кадр — сохраняем и выходим
        if (!initialized) {
            System.arraycopy(gray, 0, prev, 0, gray.size)
            initialized = true
            return
        }

        // Сравниваем (шаг 8 — сэмплируем, не все пиксели)
        var changed = 0
        var total = 0
        val step = 8
        var i = 0
        while (i < gray.size) {
            val diff = kotlin.math.abs(gray[i] - prev[i].toInt())
            if (diff > 20) changed++
            total++
            i += step
        }
        val percent = if (total > 0) changed * 100 / total else 0

        System.arraycopy(gray, 0, prev, 0, gray.size)

        val now = System.currentTimeMillis()
        if (percent > sensitivity && now - lastTrigger > cooldownMs) {
            lastTrigger = now
            println("MotionDetector: TRIGGER! percent=$percent")
            onMotion()
        }
    }
}