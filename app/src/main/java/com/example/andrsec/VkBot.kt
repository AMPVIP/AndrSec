package com.example.andrsec

import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class VkBot(
    private val token: String,
    private val groupId: Long,
    private val allowedPeerId: Long,
    private val onCommand: (String) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
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
                        continue
                    }
                    println("VkBot: Long Poll initialized OK")

                    while (running && isActive) {
                        try {
                            pollOnce()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            println("VkBot: pollOnce exception ${e.javaClass.simpleName}: ${e.message}")
                            delay(3000)
                            break
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("VkBot: outer exception ${e.javaClass.simpleName}: ${e.message}")
                    delay(5000)
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
            onError("Network: ${e.message}")
            false
        }
    }

    // ---------- Асинхронный execute с отменой ----------

    private suspend fun executeAsync(req: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) {
                        cont.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }

    // ---------- Long Poll one iteration ----------

    private suspend fun pollOnce() {
        val url = "$server?act=a_check&key=$key&ts=$ts&wait=25"
        val req = Request.Builder().url(url).build()

        val resp = executeAsync(req)
        try {
            val bodyStr = resp.body?.string()
            if (bodyStr.isNullOrEmpty()) return
            val json = JSONObject(bodyStr)

            if (json.has("failed")) {
                val failed = json.getInt("failed")
                println("VkBot: Long Poll failed=$failed")
                when (failed) {
                    1 -> ts = json.getString("ts")
                    2, 3 -> {
                        ts = ""; key = ""; server = ""
                        initLongPoll()
                    }
                    4 -> {
                        delay(5000)
                        initLongPoll()
                    }
                    else -> initLongPoll()
                }
                return
            }

            ts = json.getString("ts")
            val updates = json.optJSONArray("updates") ?: return

            if (updates.length() > 0) {
                println("VkBot: updates count=${updates.length()}, ts=$ts")
            }

            for (i in 0 until updates.length()) {
                handleUpdate(updates.getJSONObject(i))
            }
        } finally {
            resp.close()
        }
    }

    // ---------- Обработка сообщений ----------

    private fun handleUpdate(update: JSONObject) {
        if (update.optString("type") != "message_new") return

        val obj = update.getJSONObject("object")
        val message = obj.getJSONObject("message")

        if (message.optInt("out", 0) == 1) return

        val peerId = message.getLong("peer_id")
        val text = message.optString("text", "").trim().lowercase()

        if (peerId != allowedPeerId) {
            sendMessage(peerId, "⛔ У вас нет прав управлять этой системой.")
            return
        }

        when {
            text.startsWith("камера") || text == "camera" -> onCommand("TOGGLE_CAMERA")
            text.startsWith("звук") || text == "sound" -> onCommand("TOGGLE_SOUND")

            text.contains("выкл") || text == "off" -> {
                onCommand("DISARM")
                sendMessage(peerId, "⚪ Охрана снята")
            }
            text.contains("вкл") || text == "on" -> {
                onCommand("ARM")
                sendMessage(peerId, "🟢 Охрана поставлена")
            }

            text.contains("статус") || text == "status" -> onCommand("STATUS")

            text.contains("фото") || text == "photo" -> {
                sendMessage(peerId, "📸 Делаю снимок...")
                onCommand("PHOTO")
            }
            text.contains("видео") || text == "video" -> {
                sendMessage(peerId, "🎥 Записываю видео...")
                onCommand("VIDEO")
            }

            text.contains("батарея") || text == "battery" || text == "заряд" ->
                onCommand("BATTERY")

            text == "помощь" || text == "help" -> {
                sendMessage(peerId, """
                    📋 Команды:
                    • вкл/on — вкл охрану
                    • выкл/off — выкл охрану
                    • статус/status — текущее состояние
                    • фото/photo — снимок с камеры
                    • видео/video — записать видео (10 сек)
                    • звук/sound — переключить тревогу по звуку
                    • камера/camera — переключить камеру (передняя/задняя)
                    • батарея/battery — уровень заряда
                    • помощь/help — этот список
                """.trimIndent())
            }

            else -> sendMessage(peerId, "Неизвестная команда. Напишите «помощь».")
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