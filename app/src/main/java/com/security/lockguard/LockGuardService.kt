package com.security.lockguard

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class LockGuardService : AccessibilityService() {

    companion object {
        private const val TAG = "LockGuardService"
        private const val ALERT_COOLDOWN_MS = 10_000L

        @Volatile
        var isServiceRunning = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastAlertTime = 0L
    private var overlayView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        attachInvisibleAccessibilityOverlay()
        Log.i(TAG, "LockGuard AccessibilityService connected and active 24/7")
    }

    /**
     * Attaches a 1x1 transparent TYPE_ACCESSIBILITY_OVERLAY view.
     * On Android 11 (Realme UI 2.0), this keeps the service in foreground window priority
     * so Camera2 can capture front selfies even from the lock screen without any notification.
     */
    private fun attachInvisibleAccessibilityOverlay() {
        if (overlayView != null) return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = View(this)
            val params = WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            wm.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            Log.w(TAG, "Could not attach 1x1 accessibility overlay", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!PrefsManager.isPowerBlockEnabled(this)) return

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isDeviceLocked = keyguardManager.isKeyguardLocked || keyguardManager.isDeviceLocked
        if (!isDeviceLocked) return

        val className = event.className?.toString()?.lowercase() ?: ""
        val pkgName = event.packageName?.toString()?.lowercase() ?: ""

        var isPowerMenuDetected = false

        // 1. Check standard AOSP & Realme UI / ColorOS GlobalActions window class names
        if (className.contains("globalactions") ||
            className.contains("powermenu") ||
            className.contains("shutdown") ||
            className.contains("oplusglobalactions") ||
            className.contains("colorglobalactions") ||
            className.contains("oppoglobalactions")
        ) {
            isPowerMenuDetected = true
        }

        // 2. Check event text for explicit Power Off / Restart strings
        if (!isPowerMenuDetected && event.text != null) {
            for (charSeq in event.text) {
                val txt = charSeq?.toString()?.lowercase() ?: continue
                if (containsPowerMenuText(txt)) {
                    isPowerMenuDetected = true
                    break
                }
            }
        }

        // 3. Inspect active window & interactive windows if event comes from SystemUI / Android OS
        if (!isPowerMenuDetected && (pkgName.contains("systemui") || pkgName == "android" || pkgName.contains("oplus") || pkgName.contains("coloros"))) {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null && scanNodeForPowerMenu(activeRoot)) {
                isPowerMenuDetected = true
            }
            if (!isPowerMenuDetected) {
                try {
                    for (window in windows) {
                        val root = window.root ?: continue
                        if (scanNodeForPowerMenu(root)) {
                            isPowerMenuDetected = true
                            break
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        if (isPowerMenuDetected) {
            Log.w(TAG, "Power menu detected while device is LOCKED! Blocking silently.")
            blockPowerMenuAndAlert()
        }
    }

    private fun containsPowerMenuText(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false
        return t.contains("power off") ||
                t.contains("slide to power off") ||
                t.contains("swipe to power off") ||
                t.contains("swipe down to power off") ||
                t.contains("shut down") ||
                t.contains("switch off") ||
                t.contains("reboot") ||
                t == "restart" ||
                t.contains("पावर ऑफ") ||
                t.contains("स्विच ऑफ") ||
                t.contains("रीस्टार्ट")
    }

    private fun scanNodeForPowerMenu(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        if (node == null || depth > 8) return false

        val nodeText = node.text?.toString()?.lowercase() ?: ""
        val nodeDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if (containsPowerMenuText(nodeText) || containsPowerMenuText(nodeDesc)) {
            return true
        }

        // Check Realme UI / Android SystemUI GlobalActions view IDs
        if (viewId.contains("global_actions") ||
            viewId.contains("shutdown") ||
            viewId.contains("power_off") ||
            viewId.contains("oplus_global_actions") ||
            viewId.contains("color_global_actions")
        ) {
            return true
        }

        val count = node.childCount
        for (i in 0 until count) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null && scanNodeForPowerMenu(child, depth + 1)) {
                return true
            }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun dismissSystemPowerDialog() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        try {
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
    }

    private fun blockPowerMenuAndAlert() {
        // Silently dismiss the Power Menu without showing any screen or message
        dismissSystemPowerDialog()

        mainHandler.postDelayed({
            dismissSystemPowerDialog()
        }, 100L)

        mainHandler.postDelayed({
            dismissSystemPowerDialog()
        }, 250L)

        // Trigger front camera capture + email alert with 10-second cooldown
        val now = System.currentTimeMillis()
        if (PrefsManager.isPhotoAlertEnabled(this) && (now - lastAlertTime > ALERT_COOLDOWN_MS)) {
            lastAlertTime = now
            triggerIntruderCaptureAndEmail()
        }
    }

    private fun triggerIntruderCaptureAndEmail() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LockGuard::IntruderAlertWakeLock")
        wakeLock.acquire(15_000L)

        CameraCaptureHelper.captureFrontPhoto(this) { photoFile ->
            MailSender.sendIntruderAlert(
                context = this,
                reason = "Unauthorized attempt to open Power Off / Restart menu on Lock Screen",
                photoFile = photoFile
            ) { _, _ ->
                if (wakeLock.isHeld) {
                    try { wakeLock.release() } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "LockGuardService interrupted")
    }

    override fun onDestroy() {
        isServiceRunning = false
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView?.let { wm.removeView(it) }
        } catch (_: Exception) {}
        overlayView = null
        super.onDestroy()
    }
}
