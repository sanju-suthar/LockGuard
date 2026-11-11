package com.security.lockguard

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object PrefsManager {
    private const val TAG = "PrefsManager"
    private const val PREFS_NAME = "lockguard_secure_prefs"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "LockGuardAesGcmKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private const val KEY_POWER_BLOCK_ENABLED = "power_block_enabled"
    private const val KEY_PHOTO_ALERT_ENABLED = "photo_alert_enabled"
    private const val KEY_WRONG_PASS_ALERT_ENABLED = "wrong_pass_alert_enabled"
    private const val KEY_LOCATION_SHARE_ENABLED = "location_share_enabled"
    private const val KEY_AUTO_WIFI_ENABLED = "auto_wifi_enabled"
    private const val KEY_SENDER_EMAIL = "sender_email"
    private const val KEY_APP_PASSWORD_ENC = "app_password_enc"
    private const val KEY_RECEIVER_EMAIL = "receiver_email"

    private const val KEY_FAILED_PASS_COUNT = "failed_pass_count"
    private const val KEY_LAST_FAILED_PASS_TIME = "last_failed_pass_time"

    private fun getPrefs(context: Context): SharedPreferences {
        val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.applicationContext.createDeviceProtectedStorageContext()
        } else {
            context.applicationContext
        }
        return storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existingKey = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingKey != null) {
            return existingKey.secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUnlockedDeviceRequired(false)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encryptSecret(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(1 + iv.size + encrypted.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(encrypted, 0, combined, 1 + iv.size, encrypted.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            ""
        }
    }

    private fun decryptSecret(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size < 13) return ""
            val ivSize = combined[0].toInt() and 0xFF
            if (ivSize <= 0 || combined.size <= 1 + ivSize) return ""
            val iv = combined.copyOfRange(1, 1 + ivSize)
            val cipherBytes = combined.copyOfRange(1 + ivSize, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            ""
        }
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

    fun isWrongPasswordAlertEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_WRONG_PASS_ALERT_ENABLED, true)

    fun setWrongPasswordAlertEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_WRONG_PASS_ALERT_ENABLED, enabled).apply()
    }

    fun isLocationShareEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_LOCATION_SHARE_ENABLED, true)

    fun setLocationShareEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_LOCATION_SHARE_ENABLED, enabled).apply()
    }

    fun isAutoWifiEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_AUTO_WIFI_ENABLED, true)

    fun setAutoWifiEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_WIFI_ENABLED, enabled).apply()
    }

    fun incrementFailedPasswordCount(context: Context, timestampMs: Long): Int {
        val prefs = getPrefs(context)
        val nextCount = prefs.getInt(KEY_FAILED_PASS_COUNT, 0) + 1
        prefs.edit()
            .putInt(KEY_FAILED_PASS_COUNT, nextCount)
            .putLong(KEY_LAST_FAILED_PASS_TIME, timestampMs)
            .apply()
        return nextCount
    }

    fun getLastFailedPasswordTime(context: Context): Long =
        getPrefs(context).getLong(KEY_LAST_FAILED_PASS_TIME, 0L)

    fun resetFailedPasswordCount(context: Context) {
        getPrefs(context).edit()
            .putInt(KEY_FAILED_PASS_COUNT, 0)
            .putLong(KEY_LAST_FAILED_PASS_TIME, 0L)
            .apply()
    }

    fun getSenderEmail(context: Context): String =
        getPrefs(context).getString(KEY_SENDER_EMAIL, "")?.trim() ?: ""

    fun getAppPassword(context: Context): String {
        val prefs = getPrefs(context)
        val enc = prefs.getString(KEY_APP_PASSWORD_ENC, "") ?: ""
        if (enc.isNotEmpty()) {
            return decryptSecret(enc).replace(" ", "").trim()
        }
        val legacyPlain = prefs.getString("app_password", "")?.replace(" ", "")?.trim() ?: ""
        if (legacyPlain.isNotEmpty()) {
            prefs.edit()
                .putString(KEY_APP_PASSWORD_ENC, encryptSecret(legacyPlain))
                .remove("app_password")
                .apply()
            return legacyPlain
        }
        return ""
    }

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
        val cleanPass = appPassword.replace(" ", "").trim()
        getPrefs(context).edit()
            .putString(KEY_SENDER_EMAIL, senderEmail.trim())
            .putString(KEY_APP_PASSWORD_ENC, encryptSecret(cleanPass))
            .remove("app_password")
            .putString(KEY_RECEIVER_EMAIL, receiverEmail.trim())
            .apply()
    }
}
