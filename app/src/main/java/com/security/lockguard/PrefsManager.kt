package com.security.lockguard

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

object PrefsManager {
    private const val PREFS_NAME = "lockguard_secure_prefs"

    private const val KEY_POWER_BLOCK_ENABLED = "power_block_enabled"
    private const val KEY_PHOTO_ALERT_ENABLED = "photo_alert_enabled"
    private const val KEY_SENDER_EMAIL = "sender_email"
    private const val KEY_APP_PASSWORD = "app_password"
    private const val KEY_RECEIVER_EMAIL = "receiver_email"

    private fun getPrefs(context: Context): SharedPreferences {
        val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.applicationContext.createDeviceProtectedStorageContext()
        } else {
            context.applicationContext
        }
        return storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isPowerBlockEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_POWER_BLOCK_ENABLED, true)

    fun setPowerBlockEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_POWER_BLOCK_ENABLED, enabled).apply()
    }

    fun isPhotoAlertEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_PHOTO_ALERT_ENABLED, true)

    fun setPhotoAlertEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PHOTO_ALERT_ENABLED, enabled).apply()
    }

    fun getSenderEmail(context: Context): String =
        getPrefs(context).getString(KEY_SENDER_EMAIL, "")?.trim() ?: ""

    fun getAppPassword(context: Context): String =
        getPrefs(context).getString(KEY_APP_PASSWORD, "")?.replace(" ", "")?.trim() ?: ""

    fun getReceiverEmail(context: Context): String {
        val recv = getPrefs(context).getString(KEY_RECEIVER_EMAIL, "")?.trim() ?: ""
        return if (recv.isNotEmpty()) recv else getSenderEmail(context)
    }

    fun saveEmailConfig(
        context: Context,
        senderEmail: String,
        appPassword: String,
        receiverEmail: String
    ) {
        getPrefs(context).edit()
            .putString(KEY_SENDER_EMAIL, senderEmail.trim())
            .putString(KEY_APP_PASSWORD, appPassword.replace(" ", "").trim())
            .putString(KEY_RECEIVER_EMAIL, receiverEmail.trim())
            .apply()
    }
}
