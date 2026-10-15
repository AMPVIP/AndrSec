package com.example.andrsec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var batteryBtn: Button
    private var countdownJob: Job? = null
    private var isCountingDown = false   // ← НОВОЕ

    private val requiredPerms = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        toggleBtn = findViewById(R.id.toggleBtn)
        batteryBtn = findViewById(R.id.batteryBtn)

        findViewById<Button>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        requestPermissionsIfNeeded()
        refreshUi()

        FileLogger.log(this, "MainActivity: onCreate")

        getSharedPreferences("guard_prefs", MODE_PRIVATE).edit()
            .putBoolean("service_enabled", true)
            .apply()

        startServiceWithAction(GuardService.ACTION_START)
        startWatchdog()

        toggleBtn.setOnClickListener {
            if (GuardService.isRunning) {
                stopGuard()
            } else {
                startGuard()
            }
        }

        batteryBtn.setOnClickListener { requestIgnoreBatteryOptimizations() }
    }

    private fun startWatchdog() {
        val workRequest = PeriodicWorkRequestBuilder<WatchdogWorker>(
            5, java.util.concurrent.TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "guard_watchdog",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        FileLogger.log(this, "Watchdog: запланирован")
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        super.onDestroy()
    }

    private fun startGuard() {
        if (!hasAllPermissions()) {
            requestPermissionsIfNeeded()
            Toast.makeText(this, "Нужны разрешения на камеру и микрофон", Toast.LENGTH_LONG).show()
            return
        }

        isCountingDown = true
        toggleBtn.isEnabled = false
        countdownJob?.cancel()

        FileLogger.log(this, "startGuard: отсчёт 30 сек")

        countdownJob = lifecycleScope.launch(Dispatchers.Main) {
            val total = 30
            for (i in total downTo 1) {
                statusText.text = "⏳ Охрана через $i сек"
                delay(1000)
            }

            FileLogger.log(this@MainActivity, "startGuard: отсчёт завершён → ACTION_ARM")
            startServiceWithAction(GuardService.ACTION_ARM)

            getSharedPreferences("guard_prefs", MODE_PRIVATE).edit()
                .putBoolean("armed", true).apply()

            isCountingDown = false
            toggleBtn.isEnabled = true
            statusText.text = "🟢 Охрана активна"
            toggleBtn.text = "Остановить охрану"
        }
    }

    private fun stopGuard() {
        countdownJob?.cancel()
        isCountingDown = false
        toggleBtn.isEnabled = true

        startServiceWithAction(GuardService.ACTION_DISARM)

        getSharedPreferences("guard_prefs", MODE_PRIVATE).edit()
            .putBoolean("armed", false).apply()

        statusText.text = "⚪ Охрана снята (бот слушает)"
        toggleBtn.text = "Включить охрану"
    }

    private fun startServiceWithAction(action: String) {
        val intent = Intent(this, GuardService::class.java).apply {
            this.action = action
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun refreshUi() {
        // ⚠️ НЕ перезаписываем во время отсчёта
        if (isCountingDown) return

        if (GuardService.isRunning) {
            statusText.text = "🟢 Охрана активна"
            toggleBtn.text = "Остановить охрану"
        } else {
            statusText.text = "⚪ Охрана снята (бот слушает)"
            toggleBtn.text = "Включить охрану"
        }
    }

    // ---------- Разрешения ----------

    private fun hasAllPermissions() = requiredPerms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsIfNeeded() {
        val missing = requiredPerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshUi()
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Уже отключено", Toast.LENGTH_SHORT).show()
            }
        }
    }
}