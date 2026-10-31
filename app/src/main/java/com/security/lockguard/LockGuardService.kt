package com.security.lockguard

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        attachInvisibleAccessibilityOverlay()
        registerNetworkReconnectListener()
        Log.i(TAG, "LockGuard AccessibilityService connected and active 24/7")
    }

    /**
     * Automatically sends queued intruder photos and clears cache as soon as Internet turns back ON.
     */
    private fun registerNetworkReconnectListener() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.i(TAG, "Internet connection available - flushing any pending offline intruder alerts")
                    MailSender.flushPendingAlerts(this@LockGuardService)
                }
            }
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering network listener", e)
        }
    }

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

    /**
     * Hardware key filter: if the OS forwards KEYCODE_POWER while locked, block and trigger immediately.
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        if (!PrefsManager.isPowerBlockEnabled(this)) return false

        if (event.keyCode == KeyEvent.KEYCODE_POWER) {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (km.isKeyguardLocked || km.isDeviceLocked) {
                if (event.action == KeyEvent.ACTION_DOWN && event.isLongPress) {
                    blockPowerMenuAndAlert()
                    return true
                }
            }
        }
        return false
    }

    /**
     * Called ONLY on TYPE_WINDOW_STATE_CHANGED (when a new system window/dialog opens).
     * Does NOT trigger on SMS/WhatsApp notifications arriving on the lock screen.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!PrefsManager.isPowerBlockEnabled(this)) return

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isDeviceLocked = keyguardManager.isKeyguardLocked || keyguardManager.isDeviceLocked
        if (!isDeviceLocked) return

        val className = event.className?.toString()?.lowercase() ?: ""
        val pkgName = event.packageName?.toString()?.lowercase() ?: ""

        // Ignore normal lockscreen / notification shade state changes
        if (className.contains("statusbar") ||
            className.contains("notification") ||
            className.contains("keyguard") ||
            className.contains("toast")
        ) {
            return
        }

        var isPowerDialogWindow = false

        // 1. Direct check on the newly opened Window's class name (AOSP & Realme UI / ColorOS GlobalActions)
        if (className.contains("globalactions") ||
            className.contains("powermenu") ||
            className.contains("shutdown") ||
            className.contains("oplusglobalactions") ||
            className.contains("colorglobalactions") ||
            className.contains("oppoglobalactions") ||
            className.contains("reboot")
        ) {
            isPowerDialogWindow = true
        }

        // 2. If SystemUI/Android opened a Dialog/FrameLayout for the Power Menu, verify via System View IDs
        // while strictly excluding the LockScreen & Notification panel.
        if (!isPowerDialogWindow && (pkgName == "android" || pkgName.contains("systemui") || pkgName.contains("oplus") || pkgName.contains("coloros"))) {
            val root = rootInActiveWindow
            if (root != null && !isLockScreenOrNotificationWindow(root)) {
                if (isGenuinePowerMenuDialog(root)) {
                    isPowerDialogWindow = true
                }
            }
        }

        if (isPowerDialogWindow) {
            Log.w(TAG, "Genuine Power Menu window opened while device is LOCKED! Blocking silently.")
            blockPowerMenuAndAlert()
        }
    }

    /**
     * Returns true if this window is the normal Lock Screen or Notification Panel
     * (so SMS/WhatsApp notifications on the lock screen are 100% ignored).
     */
    private fun isLockScreenOrNotificationWindow(root: AccessibilityNodeInfo): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 60) {
            val node = stack.removeFirst()
            visited++
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            if (viewId.contains("notification_stack_scroller") ||
                viewId.contains("notification_panel") ||
                viewId.contains("keyguard_status_view") ||
                viewId.contains("keyguard_clock") ||
                viewId.contains("status_bar_latest_event") ||
                viewId.contains("expandablenotificationrow")
            ) {
                return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return false
    }

    /**
     * Checks whether a non-lockscreen dialog window is the actual System Power Menu
     * using system View IDs or structural Power + Restart controls.
     */
    private fun isGenuinePowerMenuDialog(root: AccessibilityNodeInfo): Boolean {
        var hasPowerOffControl = false
        var hasRestartOrEmergencyControl = false

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < 50) {
            val node = stack.removeFirst()
            visited++

            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            // Skip any notification subtree just in case
            if (viewId.contains("notification") || viewId.contains("keyguard")) {
                continue
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

            // Structural check: separate controls in the dialog (not a long SMS message)
            val txt = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim().lowercase()
            if (txt.isNotEmpty() && txt.length <= 30) {
                if (txt == "power off" || txt == "slide to power off" || txt == "swipe down to power off" || txt == "पावर ऑफ" || txt == "स्विच ऑफ") {
                    hasPowerOffControl = true
                } else if (txt == "restart" || txt == "reboot" || txt == "emergency" || txt == "emergency call" || txt == "रीस्टार्ट") {
                    hasRestartOrEmergencyControl = true
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }

        return hasPowerOffControl && hasRestartOrEmergencyControl
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
                reason = "Unauthorized attempt to Power Off / Restart device while locked",
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
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        networkCallback = null
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView?.let { wm.removeView(it) }
        } catch (_: Exception) {}
        overlayView = null
        super.onDestroy()
    }
}
