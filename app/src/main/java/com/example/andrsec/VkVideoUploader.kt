package com.example.andrsec

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object VkVideoUploader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun uploadAndGetAttachment(
        context: Context,
        videoFile: File,
        maxAttempts: Int = 3
    ): String? = withContext(Dispatchers.IO) {

        val token = BotSettings.getToken(context)
        val peerId = BotSettings.getPeerId(context)

        if (token.isEmpty() || peerId <= 0) {
            FileLogger.log(context, "VkVideoUploader: настройки VK не заполнены")
            return@withContext null
        }

        if (!videoFile.exists() || videoFile.length() == 0L) {
            FileLogger.log(context, "VkVideoUploader: файл пустой")
            return@withContext null
        }

        FileLogger.log(context, "VkVideoUploader: старт загрузки (docs), size=${videoFile.length()}")

        repeat(maxAttempts) { attempt ->
            FileLogger.log(context, "VkVideoUploader: attempt ${attempt + 1}/$maxAttempts")

            // 1. Получаем upload_url для документа
            val uploadUrl = getDocsUploadServer(context, token, peerId)
            if (uploadUrl == null) {
                FileLogger.log(context, "VkVideoUploader: getDocsUploadServer failed")
                delay(1000)
                return@repeat
            }

            // 2. Загружаем файл
            val uploadFileJson = uploadFile(context, uploadUrl, videoFile)
            if (uploadFileJson == null) {
                FileLogger.log(context, "VkVideoUploader: uploadFile failed")
                delay(1000)
                return@repeat
            }

            val fileParam = uploadFileJson.optString("file", "")
            if (fileParam.isEmpty()) {
                FileLogger.log(context, "VkVideoUploader: bad upload response, no 'file'")
                delay(1000)
                return@repeat
            }

            // 3. Сохраняем документ
            val title = "alarm_${System.currentTimeMillis()}"
            val attachment = saveDoc(context, token, fileParam, title)
            if (attachment != null) {
                FileLogger.log(context, "VkVideoUploader: attachment=$attachment")
                return@withContext attachment
            }

            FileLogger.log(context, "VkVideoUploader: saveDoc failed")
            delay(1000)
        }

        FileLogger.log(context, "VkVideoUploader: все попытки провалились")
        null
    }

    // ---------- 1. docs.getMessagesUploadServer ----------

    private fun getDocsUploadServer(context: Context, token: String, peerId: Long): String? {
        val url = "https://api.vk.com/method/docs.getMessagesUploadServer" +
                "?type=doc&peer_id=$peerId&access_token=$token&v=5.199"
        val req = Request.Builder().url(url).build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    FileLogger.log(context, "VkVideoUploader: getDocsUploadServer HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                if (json.has("error")) {
                    val err = json.getJSONObject("error")
                    FileLogger.log(context, "VkVideoUploader: getDocsUploadServer error ${err.optInt("error_code")}: ${err.optString("error_msg")}")
                    return null
                }
                json.getJSONObject("response").getString("upload_url")
            }
        } catch (e: Exception) {
            FileLogger.log(context, "VkVideoUploader: getDocsUploadServer ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ---------- 2. Загрузка файла на upload_url ----------

    private fun uploadFile(context: Context, uploadUrl: String, videoFile: File): JSONObject? {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                videoFile.name,
                videoFile.asRequestBody("video/mp4".toMediaType())
            )
            .build()

        val req = Request.Builder().url(uploadUrl).post(requestBody).build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    FileLogger.log(context, "VkVideoUploader: uploadFile HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                if (json.has("error")) {
                    FileLogger.log(context, "VkVideoUploader: uploadFile error: ${body.take(200)}")
                    return null
                }
                json
            }
        } catch (e: Exception) {
            FileLogger.log(context, "VkVideoUploader: uploadFile ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ---------- 3. docs.save ----------

    private fun saveDoc(context: Context, token: String, fileParam: String, title: String): String? {
        val url = "https://api.vk.com/method/docs.save"
        val body = FormBody.Builder()
            .add("file", fileParam)
            .add("title", title)
            .add("tags", "alarm")
            .add("access_token", token)
            .add("v", "5.199")
            .build()

        val req = Request.Builder().url(url).post(body).build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    FileLogger.log(context, "VkVideoUploader: saveDoc HTTP ${resp.code}")
                    return null
                }
                val bodyStr = resp.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                if (json.has("error")) {
                    val err = json.getJSONObject("error")
                    FileLogger.log(context, "VkVideoUploader: saveDoc error ${err.optInt("error_code")}: ${err.optString("error_msg")}")
                    return null
                }

                // docs.save возвращает объект (не массив) для type=doc
                val response = json.optJSONObject("response")
                val doc = response?.optJSONObject("doc") ?: response
                if (doc == null) {
                    FileLogger.log(context, "VkVideoUploader: saveDoc bad response: ${bodyStr.take(200)}")
                    return null
                }

                val ownerId = doc.optLong("owner_id", 0L)
                val id = doc.optLong("id", 0L)
                if (ownerId == 0L || id == 0L) {
                    FileLogger.log(context, "VkVideoUploader: saveDoc no owner_id/id")
                    return null
                }

                "doc${ownerId}_${id}"
            }
        } catch (e: Exception) {
            FileLogger.log(context, "VkVideoUploader: saveDoc ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}