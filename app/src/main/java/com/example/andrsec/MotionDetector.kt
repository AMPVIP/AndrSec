package com.example.andrsec

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class MotionDetector(
    var sensitivity: Int = 5,           // % изменённых пикселей
    private val cooldownMs: Long = 10_000,
    private val onMotion: () -> Unit
) : ImageAnalysis.Analyzer {

    private var prevGray: ByteArray? = null      // ← поле класса
    private var lastTrigger = 0L

    override fun analyze(image: ImageProxy) {
        val w = image.width
        val h = image.height

        // Копируем Y-плоскость (яркость) в НОВЫЙ массив
        val yPlane = image.planes[0].buffer
        val gray = ByteArray(w * h)
        yPlane.get(gray)
        image.close()

        val prev = prevGray
        prevGray = gray                          // сохраняем для следующего кадра

        if (prev == null || prev.size != gray.size) {
            println("MotionDetector: первый кадр, пропуск")
            return
        }

        var changed = 0
        val step = 8                             // проверяем каждый 8-й пиксель
        var i = 0
        var total = 0
        while (i < gray.size) {
            val diff = kotlin.math.abs(gray[i] - prev[i])
            if (diff > 20) changed++             // порог яркости
            total++
            i += step
        }
        val percent = changed * 100 / total
        println("MotionDetector: percent=$percent, sensitivity=$sensitivity")

        val now = System.currentTimeMillis()
        if (percent > sensitivity && now - lastTrigger > cooldownMs) {
            lastTrigger = now
            println("MotionDetector: TRIGGER!")
            onMotion()
        }
    }
}