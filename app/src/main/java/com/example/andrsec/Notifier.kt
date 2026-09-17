package com.example.andrsec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

object Notifier {
    // Заполни своими значениями
    private const val VK_TOKEN = BuildConfig.BOT_TOKEN
    private const val PEER_ID = BuildConfig.VK_ALLOWED_PEER.toString()
    private val client = OkHttpClient()

    suspend fun send(message: String) = withContext(Dispatchers.IO) {
        val url = "https://api.vk.com/method/messages.send"
        val body = FormBody.Builder()
            .add("peer_id", PEER_ID)
            .add("message", message)
            .add("random_id", (0..2_000_000_000).random().toString())
            .add("access_token", VK_TOKEN)
            .add("v", "5.199")
            .build()
        try {
            client.newCall(Request.Builder().url(url).post(body).build())
                .execute().use { }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
    suspend fun sendWithPhoto(message: String, attachment: String) = withContext(Dispatchers.IO) {
        val url = "https://api.vk.com/method/messages.send"
        val body = FormBody.Builder()
            .add("peer_id", PEER_ID)
            .add("message", message)
            .add("attachment", attachment)      // ← вложение
            .add("random_id", (0..2_000_000_000).random().toString())
            .add("access_token", VK_TOKEN)
            .add("v", "5.199")
            .build()
        try {
            client.newCall(Request.Builder().url(url).post(body).build())
                .execute().use { resp ->
                    val respBody = resp.body?.string()
                    println("Notifier.sendWithPhoto code=${resp.code} body=$respBody")
                }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}