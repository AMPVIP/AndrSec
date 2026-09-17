package com.example.andrsec

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val tokenInput = findViewById<EditText>(R.id.tokenInput)
        val groupIdInput = findViewById<EditText>(R.id.groupIdInput)
        val peerIdInput = findViewById<EditText>(R.id.peerIdInput)
        val saveBtn = findViewById<Button>(R.id.saveBtn)
        val testBtn = findViewById<Button>(R.id.testBtn)

        // Заполняем текущими значениями
        tokenInput.setText(BotSettings.getToken(this))
        val gid = BotSettings.getGroupId(this)
        val pid = BotSettings.getPeerId(this)
        if (gid > 0) groupIdInput.setText(gid.toString())
        if (pid > 0) peerIdInput.setText(pid.toString())

        saveBtn.setOnClickListener {
            val token = tokenInput.text.toString().trim()
            val groupId = groupIdInput.text.toString().trim().toLongOrNull() ?: 0L
            val peerId = peerIdInput.text.toString().trim().toLongOrNull() ?: 0L

            if (token.isEmpty() || groupId <= 0 || peerId <= 0) {
                Toast.makeText(this, "Заполните все поля корректно", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            BotSettings.save(this, token, groupId, peerId)
            Toast.makeText(this, "✅ Сохранено. Перезапустите охрану.", Toast.LENGTH_LONG).show()
            finish()
        }

        testBtn.setOnClickListener {
            val token = tokenInput.text.toString().trim()
            val groupId = groupIdInput.text.toString().trim().toLongOrNull() ?: 0L
            val peerId = peerIdInput.text.toString().trim().toLongOrNull() ?: 0L

            if (token.isEmpty() || groupId <= 0 || peerId <= 0) {
                Toast.makeText(this, "Сначала заполните все поля", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Тест: сохраняем временно и пытаемся отправить «тест»
            BotSettings.save(this, token, groupId, peerId)
            Notifier.sendTest(this) { success, message ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (success) "✅ Тест прошёл: сообщение отправлено" else "❌ Ошибка: $message",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}