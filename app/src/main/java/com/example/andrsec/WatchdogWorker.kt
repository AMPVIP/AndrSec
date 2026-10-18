package com.example.andrsec

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

class WatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        FileLogger.log(applicationContext, "Watchdog: isServiceAlive=${GuardService.isServiceAlive}, isRunning=${GuardService.isRunning}")

        if (!GuardService.isServiceAlive) {
            FileLogger.log(applicationContext, "Watchdog: служба мертва, перезапускаем")
            val intent = Intent(applicationContext, GuardService::class.java).apply {
                action = GuardService.ACTION_START
            }
            try {
                ContextCompat.startForegroundService(applicationContext, intent)
            } catch (e: Exception) {
                FileLogger.log(applicationContext, "Watchdog: ошибка ${e.message}")
                return Result.retry()
            }
        } else {
            FileLogger.log(applicationContext, "Watchdog: служба жива, всё ок")
        }
        return Result.success()
    }
}
