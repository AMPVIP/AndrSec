package com.example.andrsec

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

object Notifier {

    private val client = OkHttpClient()

    suspend fun send(context: Context, message: String) = withContext(Dispatchers.IO) {
        val token = BotSettings.getToken(context)
        val peerId = BotSettings.getPeerId(context)

        if (token.isEmpty() || peerId <= 0) {
            println("Notifier: настройки не заполнены")
            return@withContext
        }

        val url = "https://api.vk.com/method/messages.send"
        val body = FormBody.Builder()
            .add("peer_id", peerId.toString())
            .add("message", message)
            .add("random_id", (0..2_000_000_000).random().toString())
            .add("access_token", token)
            .add("v", "5.199")
            .build()

        try {
            client.newCall(Request.Builder().url(url).post(body).build())
                .execute().use { resp ->
                    val respBody = resp.body?.string()
                    if (!resp.isSuccessful) {
                        println("Notifier: send code=${resp.code} body=$respBody")
                    }
                }
        } catch (e: IOException) {
            println("Notifier: send IOException ${e.message}")
        }
    }

    suspend fun sendWithPhoto(context: Context, message: String, attachment: String) =
        withContext(Dispatchers.IO) {
            val token = BotSettings.getToken(context)
            val peerId = BotSettings.getPeerId(context)
            if (token.isEmpty() || peerId <= 0) return@withContext

            val url = "https://api.vk.com/method/messages.send"
            val body = FormBody.Builder()
                .add("peer_id", peerId.toString())
                .add("message", message)
                .add("attachment", attachment)
                .add("random_id", (0..2_000_000_000).random().toString())
                .add("access_token", token)
                .add("v", "5.199")
                .build()

            try {
                client.newCall(Request.Builder().url(url).post(body).build())
                    .execute().use { }
            } catch (e: IOException) {
                println("Notifier.sendWithPhoto: ${e.message}")
            }
        }

    /** Тестовая отправка — возвращает (успех, сообщение) */
    fun sendTest(context: Context, callback: (Boolean, String) -> Unit) {
        val token = BotSettings.getToken(context)
        val peerId = BotSettings.getPeerId(context)

        Thread {
            val url = "https://api.vk.com/method/messages.send"
            val body = FormBody.Builder()
                .add("peer_id", peerId.toString())
                .add("message", "🧪 Тест связи — всё работает!")
                .add("random_id", (0..2_000_000_000).random().toString())
                .add("access_token", token)
                .add("v", "5.199")
                .build()

            try {
                client.newCall(Request.Builder().url(url).post(body).build())
                    .execute().use { resp ->
                        val respBody = resp.body?.string() ?: ""
                        if (resp.isSuccessful && respBody.contains("\"response\"")) {
                            callback(true, "OK")
                        } else {
                            callback(false, respBody.take(200))
                        }
                    }
            } catch (e: Exception) {
                callback(false, e.message ?: "unknown")
            }
        }.start()
    }
}