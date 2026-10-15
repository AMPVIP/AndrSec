package com.example.andrsec

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {
    private const val MAX_SIZE = 500_000L   // 500 КБ
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun log(context: Context, message: String) {
        try {
            val file = File(context.filesDir, "guard.log")
            if (file.exists() && file.length() > MAX_SIZE) {
                // Перезаписываем, если слишком большой
                file.writeText("")
            }
            val line = "[${timeFormat.format(Date())}] $message\n"
            file.appendText(line)
            println("FileLogger: $message")
        } catch (e: Exception) {
            println("FileLogger: error ${e.message}")
        }
    }
}