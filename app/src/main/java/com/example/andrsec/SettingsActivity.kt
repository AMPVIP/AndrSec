package com.example.andrsec

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.andrsec.BotSettings.motionSensitivity
import com.example.andrsec.BotSettings.setMotionSensitivity
import com.example.andrsec.BotSettings.frameSkip
import com.example.andrsec.BotSettings.setFrameSkip
import android.widget.RadioButton
import android.widget.RadioGroup
class SettingsActivity : AppCompatActivity() {

    private val frameSkipValues = intArrayOf(1, 2, 4, 8)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val tokenInput = findViewById<EditText>(R.id.tokenInput)
        val groupIdInput = findViewById<EditText>(R.id.groupIdInput)
        val peerIdInput = findViewById<EditText>(R.id.peerIdInput)
        val saveBtn = findViewById<Button>(R.id.saveBtn)
        val testBtn = findViewById<Button>(R.id.testBtn)

        val sensitivitySeek = findViewById<SeekBar>(R.id.sensitivitySeek)
        val sensitivityValue = findViewById<TextView>(R.id.sensitivityValue)
        val frameSkipSeek = findViewById<SeekBar>(R.id.frameSkipSeek)
        val frameSkipValue = findViewById<TextView>(R.id.frameSkipValue)

        // --- Загрузка ---
        val savedSensitivity = this.motionSensitivity()
        sensitivitySeek.progress = savedSensitivity.coerceIn(1, 100)
        sensitivityValue.text = savedSensitivity.toString()

        // frameSkip — по массиву [1,2,4,8]
        val savedFrameSkip = this.frameSkip()
        val savedIdx = frameSkipValues.indexOf(savedFrameSkip).let { if (it < 0) 0 else it }
        frameSkipSeek.max = frameSkipValues.size - 1
        frameSkipSeek.progress = savedIdx
        updateFrameSkipText(frameSkipValue, frameSkipValues[savedIdx])
        // video or photo
        val alarmMediaGroup = findViewById<RadioGroup>(R.id.alarmMediaGroup)
        val alarmMediaPhoto = findViewById<RadioButton>(R.id.alarmMediaPhoto)
        val alarmMediaVideo = findViewById<RadioButton>(R.id.alarmMediaVideo)

        if (BotSettings.isAlarmVideo(this)) {
            alarmMediaVideo.isChecked = true
        } else {
            alarmMediaPhoto.isChecked = true
        }
        // --- Слушатель sensitivity ---
        sensitivitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(1)
                sensitivityValue.text = value.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // --- Слушатель frameSkip (по массиву) ---
        frameSkipSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = frameSkipValues[progress.coerceIn(0, frameSkipValues.lastIndex)]
                updateFrameSkipText(frameSkipValue, value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // --- VK поля ---
        tokenInput.setText(BotSettings.getToken(this))
        val gid = BotSettings.getGroupId(this)
        val pid = BotSettings.getPeerId(this)
        if (gid > 0) groupIdInput.setText(gid.toString())
        if (pid > 0) peerIdInput.setText(pid.toString())

        // --- Сохранить ---
        saveBtn.setOnClickListener {
            val token = tokenInput.text.toString().trim()
            val groupId = groupIdInput.text.toString().trim().toLongOrNull() ?: 0L
            val peerId = peerIdInput.text.toString().trim().toLongOrNull() ?: 0L

            if (token.isEmpty() || groupId <= 0 || peerId <= 0) {
                Toast.makeText(this, "Заполните все поля корректно", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            this.setMotionSensitivity(sensitivitySeek.progress.coerceIn(1, 100))
            this.setFrameSkip(frameSkipValues[frameSkipSeek.progress.coerceIn(0, frameSkipValues.lastIndex)])

            val mediaType = if (alarmMediaVideo.isChecked) {
                BotSettings.MEDIA_VIDEO
            } else {
                BotSettings.MEDIA_PHOTO
            }
            BotSettings.setAlarmMedia(this, mediaType)
            BotSettings.save(this, token, groupId, peerId)
            Toast.makeText(this, "✅ Сохранено. Перезапустите охрану.", Toast.LENGTH_LONG).show()
            finish()
        }

        // --- Тест ---
        testBtn.setOnClickListener {
            val token = tokenInput.text.toString().trim()
            val groupId = groupIdInput.text.toString().trim().toLongOrNull() ?: 0L
            val peerId = peerIdInput.text.toString().trim().toLongOrNull() ?: 0L

            if (token.isEmpty() || groupId <= 0 || peerId <= 0) {
                Toast.makeText(this, "Сначала заполните все поля", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

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

    private fun updateFrameSkipText(textView: TextView, frameSkip: Int) {
        val fps = 30 / frameSkip
        textView.text = "~$fps fps"
    }
}