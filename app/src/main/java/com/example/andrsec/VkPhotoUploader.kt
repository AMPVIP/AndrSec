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
import java.io.IOException

object VkPhotoUploader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Возвращает строку вида "photo-241543229_457239018"
     * или null, если что-то пошло не так.
     */
    suspend fun uploadAndGetAttachment(
        context: Context,
        photoFile: File,
        maxAttempts: Int = 5
    ): String? = withContext(Dispatchers.IO) {

        val token = BotSettings.getToken(context)
        val groupId = BotSettings.getGroupId(context)

        if (token.isEmpty() || groupId <= 0) {
            println("VkPhotoUploader: настройки VK не заполнены")
            return@withContext null
        }

        println("VkPhotoUploader: file size=${photoFile.length()}")

        if (!photoFile.exists() || photoFile.length() == 0L) {
            println("VkPhotoUploader: файл пустой")
            return@withContext null
        }

        repeat(maxAttempts) { attempt ->
            println("VkPhotoUploader: attempt ${attempt + 1}/$maxAttempts")

            // 1. Новый upload_url
            val uploadUrl = getUploadUrl(token, groupId)
            if (uploadUrl == null) {
                println("VkPhotoUploader: getUploadUrl failed")
                delay(500)
                return@repeat
            }

            // 2. Загрузка
            val uploadResponse = uploadFile(uploadUrl, photoFile)
            if (uploadResponse == null) {
                println("VkPhotoUploader: uploadFile null")
                delay(500)
                return@repeat
            }

            val uploadJson = JSONObject(uploadResponse)
            val server = uploadJson.optInt("server", -1)
            val photo = uploadJson.optString("photo", "")
            val hash = uploadJson.optString("hash", "")

            if (server == -1 || photo.isEmpty() || photo == "[]" || hash.isEmpty()) {
                val shortPhoto = if (photo.length > 20) photo.take(20) + "..." else photo
                println("VkPhotoUploader: bad upload (photo='$shortPhoto')")
                delay(1000)
                return@repeat
            }

            println("VkPhotoUploader: uploaded ok (${photo.length} bytes)")

            // 3. Сохранение
            val saveResponse = savePhoto(token, groupId, server, photo, hash)
            if (saveResponse == null) {
                println("VkPhotoUploader: savePhoto null")
                delay(500)
                return@repeat
            }

            val saveJson = JSONObject(saveResponse)
            if (saveJson.has("error")) {
                val errMsg = saveJson.getJSONObject("error").optString("error_msg", "unknown")
                println("VkPhotoUploader: save error: $errMsg")
                delay(500)
                return@repeat
            }

            val responseArr = saveJson.optJSONArray("response")
            if (responseArr == null || responseArr.length() == 0) {
                println("VkPhotoUploader: bad save response")
                delay(500)
                return@repeat
            }

            val photoObj = responseArr.getJSONObject(0)
            val ownerId = photoObj.getLong("owner_id")
            val id = photoObj.getLong("id")
            val attachment = "photo${ownerId}_${id}"
            println("VkPhotoUploader: attachment=$attachment")
            return@withContext attachment
        }

        println("VkPhotoUploader: все $maxAttempts попытки провалились")
        null
    }

    // ---------- 1. upload_url ----------

    private fun getUploadUrl(token: String, groupId: Long): String? {
        val url = "https://api.vk.com/method/photos.getMessagesUploadServer" +
                "?group_id=$groupId&access_token=$token&v=5.199"
        val req = Request.Builder().url(url).build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                if (json.has("error")) {
                    val errMsg = json.getJSONObject("error").optString("error_msg", "unknown")
                    val errCode = json.getJSONObject("error").optInt("error_code", -1)
                    println("VkPhotoUploader: getUploadUrl error $errCode: $errMsg")
                    return null
                }
                json.getJSONObject("response").getString("upload_url")
            }
        } catch (e: IOException) {
            println("VkPhotoUploader: getUploadUrl IOException ${e.message}")
            null
        } catch (e: Exception) {
            println("VkPhotoUploader: getUploadUrl exception ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ---------- 2. upload файла ----------

    private fun uploadFile(uploadUrl: String, photoFile: File): String? {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "photo",
                photoFile.name,
                photoFile.asRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val req = Request.Builder().url(uploadUrl).post(requestBody).build()
        return try {
            client.newCall(req).execute().use { resp ->
                resp.body?.string()
            }
        } catch (e: IOException) {
            println("VkPhotoUploader: uploadFile IOException ${e.message}")
            null
        }
    }

    // ---------- 3. save ----------

    private fun savePhoto(token: String, groupId: Long, server: Int, photo: String, hash: String): String? {
        val url = "https://api.vk.com/method/photos.saveMessagesPhoto"
        val body = FormBody.Builder()
            .add("server", server.toString())
            .add("photo", photo)
            .add("hash", hash)
            .add("group_id", groupId.toString())
            .add("access_token", token)
            .add("v", "5.199")
            .build()
        val req = Request.Builder().url(url).post(body).build()
        return try {
            client.newCall(req).execute().use { resp ->
                resp.body?.string()
            }
        } catch (e: IOException) {
            println("VkPhotoUploader: savePhoto IOException ${e.message}")
            null
        }
    }
}