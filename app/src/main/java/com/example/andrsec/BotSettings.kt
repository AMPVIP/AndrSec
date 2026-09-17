package com.example.andrsec

import android.content.Context
import android.content.SharedPreferences

object BotSettings {
    private const val PREFS_NAME = "bot_settings"
    private const val KEY_TOKEN = "vk_token"
    private const val KEY_GROUP_ID = "vk_group_id"
    private const val KEY_PEER_ID = "vk_peer_id"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    fun isConfigured(ctx: Context): Boolean {
        return getToken(ctx).isNotEmpty() &&
                getGroupId(ctx) > 0L &&
                getPeerId(ctx) > 0L
    }
}