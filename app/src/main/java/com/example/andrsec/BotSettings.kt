package com.example.andrsec

import android.content.Context
import android.content.SharedPreferences

object BotSettings {

    private const val PREFS_NAME = "bot_settings"

    private const val KEY_TOKEN = "vk_token"
    private const val KEY_GROUP_ID = "vk_group_id"
    private const val KEY_PEER_ID = "vk_peer_id"

    private const val KEY_SOUND_ENABLED = "sound_enabled"

    private const val KEY_CAMERA_FRONT = "camera_front"

    private const val KEY_MOTION_SENSITIVITY = "motion_sensitivity"
    private const val KEY_FRAME_SKIP = "frame_skip"

    // ---------- SharedPreferences ----------
    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // VK

    fun getToken(ctx: Context): String =
        prefs(ctx).getString(KEY_TOKEN, "") ?: ""

    fun getGroupId(ctx: Context): Long =
        prefs(ctx).getLong(KEY_GROUP_ID, 0L)

    fun getPeerId(ctx: Context): Long =
        prefs(ctx).getLong(KEY_PEER_ID, 0L)

    fun save(ctx: Context, token: String, groupId: Long, peerId: Long) {
        prefs(ctx).edit()
            .putString(KEY_TOKEN, token.trim())
            .putLong(KEY_GROUP_ID, groupId)
            .putLong(KEY_PEER_ID, peerId)
            .apply()
    }

    fun isConfigured(ctx: Context): Boolean =
        getToken(ctx).isNotEmpty() &&
                getGroupId(ctx) > 0L &&
                getPeerId(ctx) > 0L

    // Звук

    fun isSoundEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SOUND_ENABLED, false)

    fun setSoundEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }

    fun toggleSound(ctx: Context): Boolean {
        val newValue = !isSoundEnabled(ctx)
        setSoundEnabled(ctx, newValue)
        return newValue
    }

    // Камера (front/back)

    fun isFrontCamera(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CAMERA_FRONT, false)

    fun setFrontCamera(ctx: Context, front: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CAMERA_FRONT, front).apply()
    }

    fun toggleCamera(ctx: Context): Boolean {
        val newValue = !isFrontCamera(ctx)
        setFrontCamera(ctx, newValue)
        return newValue
    }

    // MotionDetector sensitivity (extension)

    fun Context.motionSensitivity(): Int =
        prefs(this).getInt(KEY_MOTION_SENSITIVITY, 5)

    fun Context.setMotionSensitivity(value: Int) {
        prefs(this).edit()
            .putInt(KEY_MOTION_SENSITIVITY, value.coerceIn(1, 100))
            .apply()
    }

    // CameraX FPS (extension)

    fun Context.frameSkip(): Int =
        prefs(this).getInt(KEY_FRAME_SKIP, 1)   // 1 = полный поток

    fun Context.setFrameSkip(value: Int) {
        // Ограничиваем 1, 2, 4, 8 (можно расширить)
        val safeValue = if (value in listOf(1, 2, 4, 8)) value else 1
        prefs(this).edit()
            .putInt(KEY_FRAME_SKIP, safeValue)
            .apply()
    }
    // ---------- Тип медиа при тревоге ----------

    private const val KEY_ALARM_MEDIA = "alarm_media"   // "photo" | "video"
    const val MEDIA_PHOTO = "photo"
    const val MEDIA_VIDEO = "video"

    fun getAlarmMedia(ctx: Context): String =
        prefs(ctx).getString(KEY_ALARM_MEDIA, MEDIA_PHOTO) ?: MEDIA_PHOTO

    fun setAlarmMedia(ctx: Context, value: String) {
        val safe = if (value == MEDIA_VIDEO) MEDIA_VIDEO else MEDIA_PHOTO
        prefs(ctx).edit().putString(KEY_ALARM_MEDIA, safe).apply()
    }

    fun isAlarmVideo(ctx: Context): Boolean = getAlarmMedia(ctx) == MEDIA_VIDEO
}