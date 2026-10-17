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
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.Locale
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import java.text.SimpleDateFormat
import kotlinx.coroutines.delay

class GuardService : LifecycleService() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_ARM = "ACTION_ARM"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_DISARM = "ACTION_DISARM"
        const val NOTIF_ID = 1001

        @Volatile
        var isRunning = false
            private set
        @Volatile
        var isServiceAlive = false
            private set
    }

    private val soundDetector = SoundDetector(thresholdDb = 200.0) { db ->
        onAlarm("🔊 Громкий звук: ${"%.1f".format(db)} dB")
    }
    private var vkBot: VkBot? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        FileLogger.log(this, "GuardService: onCreate")
        isServiceAlive = true
        isRunning = false
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        FileLogger.log(this, "onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                fullStop()
                return START_NOT_STICKY
            }
            ACTION_DISARM -> {
                stopGuard()
            }
            ACTION_ARM -> {
                startGuardFromRemote()
            }
            else -> {
                // ⚠️ Старт службы — только бот. Охрана НЕ включается.
                startForegroundCompat(withCamera = false)
                startVkBot()
                startCamera()

                // Через 3 сек — выставить пороги «выключено»
                lifecycleScope.launch(Dispatchers.Main) {
                    delay(3000)
                    motionDetector?.sensitivity = 100
                    soundDetector.thresholdDb = 200.0
                    FileLogger.log(this@GuardService, "служба запущена, пороги максимальные")
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat(withCamera: Boolean = false) {
        val notification = buildNotification(
            if (withCamera) "🟢 Охрана активна" else "⚪ Охрана снята",
            if (withCamera) "Слежу за движением и звуком" else "Бот слушает команды"
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val types = if (withCamera) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                startForeground(NOTIF_ID, notification, types)
                println("GuardService: startForeground OK, withCamera=$withCamera")
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            println("GuardService: startForeground FAILED: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            try {
                startForeground(NOTIF_ID, notification)
            } catch (e2: Exception) {
                println("GuardService: fallback startForeground FAILED: ${e2.message}")
            }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForegroundCompat(withCamera = true)
        }
        FileLogger.log(this, "startGuard: охрана включается")
        startCamera()
        lifecycleScope.launch(Dispatchers.Main) {
            delay(3000)
            motionDetector?.sensitivity = 5

            if (BotSettings.isSoundEnabled(this@GuardService)) {
                soundDetector.thresholdDb = 70.0
                soundDetector.start(lifecycleScope)
                FileLogger.log(this@GuardService, "startGuard: звук ВКЛ")
            } else {
                soundDetector.thresholdDb = 200.0
                soundDetector.stop()
                FileLogger.log(this@GuardService, "startGuard: звук ОТКЛ")
            }
        }
        isRunning = true
        updateNotification("🟢 Охрана активна", "Слежу за движением")
    }

    private fun startVkBot() {
        if (vkBot != null) return

        val token = BotSettings.getToken(this)
        val groupId = BotSettings.getGroupId(this)
        val peerId = BotSettings.getPeerId(this)

        if (token.isEmpty() || groupId <= 0 || peerId <= 0) {
            FileLogger.log(this, "настройки VK не заполнены")
            return
        }

        vkBot = VkBot(
            token = token,
            groupId = groupId,
            allowedPeerId = peerId,
            onCommand = { cmd ->
                lifecycleScope.launch(Dispatchers.Main) {
                    when (cmd) {
                        "ARM" -> startGuardFromRemote()
                        "DISARM" -> stopGuard()
                        "PHOTO" -> takePhotoAndSend("📸 Снимок по запросу")
                        "STATUS" -> {
                            val statusText = if (isRunning) "🟢 на охране" else "⚪ снята"
                            val soundStatus = if (BotSettings.isSoundEnabled(this@GuardService)) {
                                "🔊 Звук: ВКЛ"
                            } else {
                                "🔇 Звук: ОТКЛ"
                            }
                            val batteryInfo = getBatteryInfo()
                            val message = "📊 Статус:\n$statusText\n$soundStatus\n\n$batteryInfo"
                            lifecycleScope.launch(Dispatchers.IO) {
                                Notifier.send(this@GuardService, message)
                            }
                        }
                        "BATTERY" -> {
                            val info = getBatteryInfo()
                            lifecycleScope.launch(Dispatchers.IO) {
                                Notifier.send(this@GuardService, "🔋 Батарея:\n$info")
                            }
                        }
                        "TOGGLE_SOUND" -> {
                            val newValue = BotSettings.toggleSound(this@GuardService)
                            FileLogger.log(this@GuardService, "звук переключён: $newValue")

                            if (isRunning) {
                                if (newValue) {
                                    soundDetector.thresholdDb = 70.0
                                    if (!soundDetector.isRunning) {
                                        soundDetector.start(lifecycleScope)
                                    }
                                    FileLogger.log(this@GuardService, "звук включён (70 dB)")
                                } else {
                                    soundDetector.thresholdDb = 200.0
                                    soundDetector.stop()
                                    FileLogger.log(this@GuardService, "звук отключён (200 dB)")
                                }
                            }

                            val reply = if (newValue) "🔊 Тревога по звуку ВКЛЮЧЕНА" else "🔇 Тревога по звуку ОТКЛЮЧЕНА"
                            lifecycleScope.launch(Dispatchers.IO) {
                                Notifier.send(this@GuardService, reply)
                            }
                        }
                    }
                }
            },
            onError = { msg -> FileLogger.log(this, "VkBot: $msg") }
        ).also { it.start(lifecycleScope) }

        FileLogger.log(this, "VkBot запущен")
    }

    private fun stopGuard() {
        soundDetector.thresholdDb = 200.0
        motionDetector?.sensitivity = 100

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForegroundCompat(withCamera = false)
        }
        isRunning = false
        FileLogger.log(this, "stopGuard: охрана снята, sensitivity=100, thresholdDb=200")
        updateNotification("⚪ Охрана снята", "Бот слушает команды")
    }

    private fun fullStop() {
        FileLogger.log(this, "fullStop")
        soundDetector.stop()
        cameraProvider?.unbindAll()
        vkBot?.stop()
        vkBot = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startGuardFromRemote() {
        if (isRunning) return
        isRunning = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForegroundCompat(withCamera = true)
        }

        if (soundDetector.isRunning && imageCapture != null) {
            soundDetector.thresholdDb = 70.0
            motionDetector?.sensitivity = 5
            FileLogger.log(this, "охрана поставлена, пороги восстановлены")
        } else {
            startGuard()
            FileLogger.log(this, "охрана поставлена, полный рестарт")
        }

        updateNotification("🟢 Охрана активна", "Слежу за движением и звуком")
    }

    private var imageCapture: ImageCapture? = null
    private var motionDetector: MotionDetector? = null

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                // ⚠️ sensitivity = 100 (по умолчанию отключено)
                motionDetector = MotionDetector(sensitivity = 100) {
                    onAlarm("🎥 Обнаружено движение!")
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, motionDetector!!)
                    }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetResolution(android.util.Size(1280, 720))
                    .setJpegQuality(70)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis,
                    imageCapture
                )

                FileLogger.log(this, "камера привязана, motionDetector=$motionDetector")
            } catch (e: Exception) {
                e.printStackTrace()
                FileLogger.log(this, "ошибка камеры: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhotoAndSend(reason: String) {
        val capture = imageCapture
        if (capture == null) {
            FileLogger.log(this, "imageCapture == null, пропускаем фото")
            return
        }

        val photoDir = File(cacheDir, "photos").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        val photoFile = File(photoDir, "alarm_$timestamp.jpg")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    FileLogger.log(this@GuardService, "фото сохранено")
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val attachment = VkPhotoUploader.uploadAndGetAttachment(this@GuardService, photoFile)
                            if (attachment != null) {
                                Notifier.sendWithPhoto(this@GuardService,
                                    "🚨 ТРЕВОГА!\n$reason\nВремя: ${java.util.Date()}",
                                    attachment
                                )
                            } else {
                                Notifier.send(this@GuardService, "🚨 ТРЕВОГА!\n$reason\n(фото не загрузилось)")
                            }
                        } catch (e: Exception) {
                            FileLogger.log(this@GuardService, "upload exception ${e.message}")
                            Notifier.send(this@GuardService, "🚨 ТРЕВОГА!\n$reason\n(ошибка загрузки фото)")
                        } finally {
                            photoFile.delete()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    FileLogger.log(this@GuardService, "takePicture error ${exception.message}")
                    lifecycleScope.launch(Dispatchers.IO) {
                        Notifier.send(this@GuardService, "🚨 ТРЕВОГА!\n$reason\n(фото не удалось снять)")
                    }
                }
            }
        )
    }

    private fun onAlarm(reason: String) {
        if (!isRunning) {
            FileLogger.log(this, "onAlarm: охрана снята, игнорируем: $reason")
            return
        }
        FileLogger.log(this, "ТРЕВОГА: $reason")
        takePhotoAndSend(reason)
    }

    // ---------- Battery ----------
    private fun getBatteryInfo(): String {
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager

        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
        val charging = when (status) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "⚡ Заряжается"
            android.os.BatteryManager.BATTERY_STATUS_FULL -> "🔌 Полный"
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "🔋 Разряжается"
            android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "⏸ Не заряжается"
            else -> "❓ Неизвестно"
        }

        val intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC = tempTenths / 10.0
        val voltageMv = intent?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val voltageV = voltageMv / 1000.0

        return """
        🔋 Уровень: $level%
        $charging
        🌡 Температура: ${"%.1f".format(tempC)}°C
        ⚡ Напряжение: ${"%.2f".format(voltageV)} В
    """.trimIndent()
    }

    // ---------- WakeLock ----------
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GuardService::WakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    override fun onDestroy() {
        FileLogger.log(this, "GuardService: onDestroy")
        isServiceAlive = false
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