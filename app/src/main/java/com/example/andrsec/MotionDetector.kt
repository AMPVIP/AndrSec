package com.example.andrsec

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class MotionDetector(
    var sensitivity: Int = 100,
    private val cooldownMs: Int = 10_000,
    private val frameSkip: Int = 1,
    private val onMotion: () -> Unit
) : ImageAnalysis.Analyzer {

    // Переиспользуемые буферы
    private var grayBuffer: ByteArray? = null
    private var prevGray: ByteArray? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var frameCount = 0L
    private var lastTrigger = 0L

    override fun analyze(image: ImageProxy) {
        val w = image.width
        val h = image.height
        if (frameCount++ % frameSkip != 0L) {
            image.close()
            return
        }

        // Пересоздаём буферы ТОЛЬКО при смене разрешения
        if (grayBuffer == null || lastWidth != w || lastHeight != h) {
            grayBuffer = ByteArray(w * h)
            prevGray = ByteArray(w * h)
            lastWidth = w
            lastHeight = h
            println("MotionDetector: буферы пересозданы ${w}x$h")
        }

        val gray = grayBuffer!!
        val prev = prevGray!!

        // Копируем Y-плоскость в переиспользуемый буфер
        image.planes[0].buffer.get(gray)
        image.close()

        // Первый кадр — сохраняем и выходим
        if (lastTrigger == 0L) {
            System.arraycopy(gray, 0, prev, 0, gray.size)
            lastTrigger = System.currentTimeMillis() - cooldownMs   // разрешаем сразу
            return
        }

        // Сравниваем
        var changed = 0
        val step = 8
        var i = 0
        var total = 0
        while (i < gray.size) {
            val diff = kotlin.math.abs(gray[i] - prev[i].toInt())
            if (diff > 20) changed++
            total++
            i += step
        }
        val percent = changed * 100 / total

        System.arraycopy(gray, 0, prev, 0, gray.size)

        // Логируем только TRIGGER (не спамим)
        val now = System.currentTimeMillis()
        if (percent > sensitivity && now - lastTrigger > cooldownMs) {
            lastTrigger = now
            println("MotionDetector: TRIGGER! percent=$percent")
            onMotion()
        }
    }
}