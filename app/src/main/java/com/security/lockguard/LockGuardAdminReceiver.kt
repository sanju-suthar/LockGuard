package com.security.lockguard

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.UserHandle
import android.util.Log

class LockGuardAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "LockGuardAdminReceiver"
        // Lockscreen timeout timer is typically 30 seconds after 5 failed attempts
        private const val LOCKOUT_TIMER_THRESHOLD_MS = 25_000L
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        PrefsManager.resetFailedPasswordCount(context)
        Log.i(TAG, "LockGuard Device Admin enabled for Wrong Password detection")
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        PrefsManager.resetFailedPasswordCount(context)
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordSucceeded(context, intent, user)
        PrefsManager.resetFailedPasswordCount(context)
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        handleFailedPasswordAttempt(context)
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordFailed(context, intent, user)
        handleFailedPasswordAttempt(context)
    }

    private fun handleFailedPasswordAttempt(context: Context) {
        if (!PrefsManager.isWrongPasswordAlertEnabled(context)) return

        val appCtx = context.applicationContext
        val now = System.currentTimeMillis()

        // Guard against duplicate callbacks within 800ms on dual-method OEM ROMs
        val lastFailTime = PrefsManager.getLastFailedPasswordTime(appCtx)
        if (now - lastFailTime in 1..800) {
            return
        }

        val timeSinceLastFail = if (lastFailTime > 0L) (now - lastFailTime) else 0L
        val dpm = appCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val osFailedCount = try { dpm.currentFailedPasswordAttempts } catch (_: Exception) { 0 }

        val customCount = PrefsManager.incrementFailedPasswordCount(appCtx, now)
        val totalAttempts = maxOf(osFailedCount, customCount)

        // Check if this attempt happened after a Lockout Timeout Timer (>= 25 seconds gap after previous fails)
        val isAfterTimeoutTimer = (totalAttempts > 2 && timeSinceLastFail >= LOCKOUT_TIMER_THRESHOLD_MS) ||
                (totalAttempts >= 5)

        // Trigger rules requested by user:
        // 1. Trigger on the 2nd wrong attempt (totalAttempts == 2)
        // 2. Trigger on any wrong attempt after the timeout timer (or every 2nd attempt before timeout)
        val shouldTriggerAlert = (totalAttempts == 2) ||
                isAfterTimeoutTimer ||
                (totalAttempts > 2 && totalAttempts % 2 == 0)

        Log.w(
            TAG,
            "Wrong PIN/Pattern/Password attempt #$totalAttempts (gap=${timeSinceLastFail}ms, trigger=$shouldTriggerAlert)"
        )

        if (!shouldTriggerAlert) return

        val reasonText = if (isAfterTimeoutTimer && totalAttempts > 2) {
            "Incorrect PIN / Pattern / Password entered again after Lockout Timer (Failed Attempt #$totalAttempts)"
        } else {
            "Incorrect PIN / Pattern / Password entered on Lock Screen ($totalAttempts Failed Attempts)"
        }

        val pm = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LockGuard::WrongPasswordWakeLock"
        )
        wakeLock.acquire(15_000L)

        CameraCaptureHelper.captureFrontPhoto(appCtx) { photoFile ->
            MailSender.sendIntruderAlert(
                context = appCtx,
                reason = reasonText,
                photoFile = photoFile
            ) { _, _ ->
                if (wakeLock.isHeld) {
                    try { wakeLock.release() } catch (_: Exception) {}
                }
            }
        }
    }
}
