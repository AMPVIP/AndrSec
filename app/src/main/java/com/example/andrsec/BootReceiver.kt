package com.example.andrsec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // 1. Watchdog (если добавишь)
        // WatchdogReceiver.scheduleNext(context)

        val prefs = context.getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
        val wasArmed = prefs.getBoolean("armed", false)

        val serviceIntent = Intent(context, GuardService::class.java).apply {
            action = if (wasArmed) GuardService.ACTION_ARM else GuardService.ACTION_START
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}