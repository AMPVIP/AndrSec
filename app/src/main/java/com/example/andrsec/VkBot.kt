package com.example.andrsec

import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class VkBot(
    private val token: String,
    private val groupId: Long,
    private val allowedPeerId: Long,          // только этот peer_id может управлять
    private val onCommand: (String) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS) // long poll
        .build()

    private var server: String = ""
    private var key: String = ""
    private var ts: String = ""
    private var running = false
    private var pollJob: Job? = null

    fun start(scope: CoroutineScope) {
        println("VkBot.start() called, running=$running")
        if (running) return
        running = true

        pollJob = scope.launch(Dispatchers.IO) {
            while (running && isActive) {
                try {
                    if (!initLongPoll()) {
                        println("VkBot: initLongPoll failed, retry in 10s")
                        delay(10_000)
                        continue   // ← не выходим, а пробуем снова
                    }
                    println("VkBot: Long Poll initialized OK")

                    // Внутренний цикл — пока сессия жива
                    while (running && isActive) {
                        try {
                            pollOnce()
                        } catch (e: Exception) {
                            println("VkBot: pollOnce exception ${e.javaClass.simpleName}: ${e.message}")
                            delay(3000)
                            break   // ← выходим из внутреннего цикла, переинициализация
                        }
                    }
                } catch (e: Exception) {
                    println("VkBot: outer exception ${e.javaClass.simpleName}: ${e.message}")
                    delay(5000)
                    // не выходим — продолжаем цикл
                }
            }
            println("VkBot: poll loop ended")
        }
    }

    fun stop() {
        running = false
        pollJob?.cancel()
        pollJob = null
    }

    // ---------- Long Poll init ----------

    private fun initLongPoll(): Boolean {
        val url = "https://api.vk.com/method/groups.getLongPollServer" +
                "?group_id=$groupId&access_token=$token&v=5.199"
        val req = Request.Builder().url(url).build()
        return try {
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string()
                println("VkBot: initLongPoll code=${resp.code} body=${bodyStr?.take(200)}")

                if (bodyStr.isNullOrEmpty()) {
                    onError("Пустой ответ от VK")
                    return false
                }
                val json = JSONObject(bodyStr)
                if (json.has("error")) {
                    onError("VK: ${json.getJSONObject("error").getString("error_msg")}")
                    return false
                }
                val r = json.getJSONObject("response")
                server = r.getString("server")
                key = r.getString("key")
                ts = r.getString("ts")
                println("VkBot: initLongPoll OK")
                true
            }
        } catch (e: Exception) {
            println("VkBot: initLongPoll exception ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            onError("Network: ${e.message}")
            false
        }
    }

    // ---------- Long Poll one iteration ----------

    private suspend fun pollOnce() {
        val url = "$server?act=a_check&key=$key&ts=$ts&wait=25"
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string()

            if (bodyStr.isNullOrEmpty()) return
            val json = JSONObject(bodyStr)

            if (json.has("failed")) {
                val failed = json.getInt("failed")
                println("VkBot: Long Poll failed=$failed")
                when (failed) {
                    1 -> {
                        // ts устарел — обновить
                        ts = json.getString("ts")
                    }
                    2, 3 -> {
                        // key/server устарели — полная переинициализация
                        ts = ""; key = ""; server = ""
                        initLongPoll()
                    }
                    4 -> {
                        // Лимит запросов — подождать
                        delay(5000)
                        initLongPoll()
                    }
                    else -> {
                        // Неизвестный код — переинициализация
                        initLongPoll()
                    }
                }
                return
            }

            ts = json.getString("ts")
            val updates = json.optJSONArray("updates") ?: return

            // Логируем только если есть события
            if (updates.length() > 0) {
                println("VkBot: updates count=${updates.length()}, ts=$ts")
            }

            for (i in 0 until updates.length()) {
                handleUpdate(updates.getJSONObject(i))
            }
        }
    }

    // ---------- Обработка сообщений ----------

    private fun handleUpdate(update: JSONObject) {
        if (update.optString("type") != "message_new") return

        val obj = update.getJSONObject("object")
        val message = obj.getJSONObject("message")
        val peerId = message.getLong("peer_id")
        val text = message.optString("text", "").trim().lowercase()

        // Фильтр: только твой peer_id может управлять
        if (peerId != allowedPeerId) {
            sendMessage(peerId, "⛔ У вас нет прав управлять этой системой.")
            return
        }

        when {
            text.contains("вкл") || text == "on" -> {
                onCommand("ARM")
                sendMessage(peerId, "🟢 Охрана поставлена")
            }
            text.contains("выкл") || text == "off" -> {
                onCommand("DISARM")
                sendMessage(peerId, "⚪ Охрана снята")
            }
            text.contains("статус") || text == "status" -> {
                val status = if (GuardService.isRunning) "🟢 на охране" else "⚪ снята"
                sendMessage(peerId, "Статус: $status")
            }
            text.contains("фото") || text == "photo" -> {
                sendMessage(peerId, "📸 Делаю снимок...")
                onCommand("PHOTO")
            }
            text.contains("батарея") || text == "battery" || text == "заряд" -> {
                onCommand("BATTERY")
                // Ответ придёт из GuardService — не отправляем здесь
            }
            text == "помощь" || text == "help" -> {
                sendMessage(peerId, """
        📋 Команды:
        • вкл/on — вкл охрану
        • выкл/off — выкл охрану
        • статус/status — текущее состояние
        • фото/photo — снимок с камеры
        • батарея/battery — уровень заряда
        • помощь/help — этот список
    """.trimIndent())
            }
            else -> {
                sendMessage(peerId, "Неизвестная команда. Напишите «помощь».")
            }
        }
    }

    // ---------- Отправка сообщения ----------

    fun sendMessage(peerId: Long, text: String) {
        val url = "https://api.vk.com/method/messages.send"
        val body = FormBody.Builder()
            .add("peer_id", peerId.toString())
            .add("message", text)
            .add("random_id", (0..2_000_000_000).random().toString())
            .add("access_token", token)
            .add("v", "5.199")
            .build()
        try {
            client.newCall(Request.Builder().url(url).post(body).build())
                .execute().use { }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}