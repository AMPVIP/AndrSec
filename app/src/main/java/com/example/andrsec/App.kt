package com.example.andrsec

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class App : Application() {

    companion object {
        const val CHANNEL_ID = "guard_channel"
        const val CHANNEL_ALARM_ID = "guard_alarm_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Явно указываем тип — Context.NOTIFICATION_SERVICE
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Охрана (фоновая служба)",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Постоянное уведомление о работе охраны"
                setShowBadge(false)
                enableVibration(false)                          // ← без вибрации
                setSound(null, null)            // ← без звука
            }

            val alarmChannel = NotificationChannel(
                CHANNEL_ALARM_ID,
                "Тревоги",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о срабатывании датчиков"
                enableVibration(true)
            }

            nm.createNotificationChannel(serviceChannel)
            nm.createNotificationChannel(alarmChannel)
        }
    }
}