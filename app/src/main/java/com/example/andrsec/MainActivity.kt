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

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var batteryBtn: Button

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

        requestPermissionsIfNeeded()
        refreshUi()

        toggleBtn.setOnClickListener {
            if (GuardService.isRunning) stopGuard() else startGuard()
            // Небольшая задержка, чтобы служба успела переключиться
            statusText.postDelayed({ refreshUi() }, 300)
        }

        batteryBtn.setOnClickListener { requestIgnoreBatteryOptimizations() }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun startGuard() {
        if (!hasAllPermissions()) {
            requestPermissionsIfNeeded()
            Toast.makeText(this, "Нужны разрешения на камеру и микрофон", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, GuardService::class.java).apply {
            action = GuardService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)

        getSharedPreferences("guard_prefs", MODE_PRIVATE).edit()
            .putBoolean("armed", true).apply()
    }

    private fun stopGuard() {
        val intent = Intent(this, GuardService::class.java).apply {
            action = GuardService.ACTION_STOP
        }
        startService(intent)

        getSharedPreferences("guard_prefs", MODE_PRIVATE).edit()
            .putBoolean("armed", false).apply()
    }

    private fun refreshUi() {
        if (GuardService.isRunning) {
            statusText.text = "🟢 Охрана включена (работает в фоне)"
            toggleBtn.text = "Остановить охрану"
        } else {
            statusText.text = "⚪ Охрана выключена"
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

    // ---------- Battery optimization ----------

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