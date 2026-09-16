package com.example.andrsec

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService          // ← ключевой
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
class GuardService : LifecycleService() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_DISARM = "ACTION_DISARM"
        const val NOTIF_ID = 1001

        @Volatile
        var isRunning = false
            private set
        private const val VK_TOKEN = BuildConfig.BOT_TOKEN
        private val VK_GROUP_ID = BuildConfig.VK_GROUP_ID.toLong()
        private val VK_ALLOWED_PEER = BuildConfig.VK_ALLOWED_PEER.toLong()
    }

    private val soundDetector = SoundDetector(thresholdDb = 70.0) { db ->
        onAlarm("🔊 Громкий звук: ${"%.1f".format(db)} dB")
    }
    private var vkBot: VkBot? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                // Полная остановка (из уведомления или кнопки)
                fullStop()
                return START_NOT_STICKY
            }
            ACTION_DISARM -> {
                // Только выключить камеру/микрофон, служба остаётся
                stopGuard()
            }
            else -> {
                startForegroundCompat()
                startGuard()
                startVkBot()
            }
        }
        return START_STICKY
    }

    // ---------- Foreground ----------

    private fun startForegroundCompat() {
        val notification = buildNotification("🟢 Охрана активна", "Слежу за движением и звуком")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, notification, types)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(title: String, text: String, isAlarm: Boolean = false): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, GuardService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val channelId = if (isAlarm) App.CHANNEL_ALARM_ID else App.CHANNEL_ID
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(!isAlarm)
            .setOnlyAlertOnce(!isAlarm)
            .setPriority(
                if (isAlarm) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_LOW
            )

        if (!isAlarm) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stopPi)
        }
        return builder.build()
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(title, text))
    }

    // ---------- Охрана ----------

    private fun startGuard() {
        soundDetector.start(lifecycleScope)
        startCamera()
        updateNotification("🟢 Охрана активна", "Слежу за движением и звуком")
    }
    private fun startVkBot() {
        if (vkBot != null) return
        vkBot = VkBot(
            token = VK_TOKEN,
            groupId = VK_GROUP_ID,
            allowedPeerId = VK_ALLOWED_PEER,
            onCommand = { cmd ->
                // ⚠️ VkBot вызывает это из Dispatchers.IO — переключаемся на Main
                lifecycleScope.launch(Dispatchers.Main) {
                    when (cmd) {
                        "ARM" -> startGuardFromRemote()
                        "DISARM" -> stopGuard()
                    }
                }
            },
            onError = { msg -> println("VkBot: $msg") }
        ).also { it.start(lifecycleScope) }
    }
    private fun stopGuard() {
        soundDetector.stop()
        cameraProvider?.unbindAll()
        isRunning = false
        updateNotification("⚪ Охрана снята", "Бот слушает команды")
    }
    private fun fullStop() {
        soundDetector.stop()
        cameraProvider?.unbindAll()
        vkBot?.stop()
        vkBot = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    private fun startGuardFromRemote() {
        if (isRunning && soundDetector.isRunning) {
            // Уже на охране — ничего не делаем
            return
        }
        // Запускаем всё заново
        isRunning = true
        startGuard()
        updateNotification("🟢 Охрана активна", "Слежу за движением и звуком")
    }
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                // Preview не нужен — только анализ. Это экономит батарею.
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()
                    .also {
                        it.setAnalyzer(
                            cameraExecutor,
                            MotionDetector(sensitivity = 5) {
                                onAlarm("🎥 Обнаружено движение!")
                            }
                        )
                    }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
                onAlarm("❌ Ошибка камеры: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onAlarm(reason: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(
            NOTIF_ID + 1,
            buildNotification("🚨 ТРЕВОГА", reason, isAlarm = true)
        )

        lifecycleScope.launch {
            Notifier.send("🚨 ТРЕВОГА!\n$reason\nВремя: ${java.util.Date()}")
        }
    }

    // ---------- WakeLock ----------

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GuardService::WakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L) // максимум 10 часов
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    // ---------- Lifecycle ----------

    override fun onDestroy() {
        isRunning = false
        soundDetector.stop()
        vkBot?.stop()
        vkBot = null
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
