package com.security.lockguard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.AuthenticationFailedException
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

object MailSender {
    private const val TAG = "MailSender"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isInternetAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Automatically turns ON Wi-Fi when offline and connects to any saved Wi-Fi
     * or the strongest nearby Open Wi-Fi network.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun tryAutoEnableAndConnectWifi(context: Context) {
        if (!PrefsManager.isAutoWifiEnabled(context)) return
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifiManager.isWifiEnabled) {
                Log.i(TAG, "Internet OFF -> Automatically enabling Wi-Fi...")
                wifiManager.isWifiEnabled = true
            }

            wifiManager.startScan()
            mainHandler.postDelayed({
                executor.execute {
                    try {
                        if (isInternetAvailable(context)) return@execute
                        val hasLoc = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!hasLoc) {
                            wifiManager.reconnect()
                            return@execute
                        }

                        val results = wifiManager.scanResults ?: return@execute
                        val openNetworks = results
                            .filter { scan ->
                                val ssid = scan.SSID ?: ""
                                val caps = (scan.capabilities ?: "").uppercase(Locale.US)
                                ssid.isNotBlank() &&
                                        !caps.contains("WEP") &&
                                        !caps.contains("PSK") &&
                                        !caps.contains("SAE") &&
                                        !caps.contains("EAP")
                            }
                            .sortedByDescending { it.level }

                        if (openNetworks.isNotEmpty()) {
                            val bestOpen = openNetworks.first()
                            Log.i(TAG, "Connecting to nearby Open Wi-Fi: ${bestOpen.SSID}")
                            val conf = WifiConfiguration().apply {
                                SSID = "\"${bestOpen.SSID}\""
                                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                            }
                            val netId = wifiManager.addNetwork(conf)
                            if (netId != -1) {
                                wifiManager.disconnect()
                                wifiManager.enableNetwork(netId, true)
                                wifiManager.reconnect()
                            }
                        } else {
                            wifiManager.reconnect()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Open Wi-Fi auto-connect error", e)
                    }
                }
            }, 4000L)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to auto-enable Wi-Fi", e)
        }
    }

    /**
     * Thread-safe GPS/Network location fetcher with exact fix timestamp & age reporting.
     */
    @SuppressLint("MissingPermission")
    fun getDeviceLocationString(context: Context): String {
        if (!PrefsManager.isLocationShareEnabled(context)) {
            return "Location sharing is disabled in app settings."
        }

        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            return "Location permission not granted (Grant Location permission in LockGuard app)."
        }

        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val freshLocationRef = AtomicReference<Location?>(null)
            val latch = CountDownLatch(1)

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    freshLocationRef.set(location)
                    latch.countDown()
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            mainHandler.post {
                try {
                    if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
                    }
                    if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
                    }
                } catch (_: SecurityException) {
                    latch.countDown()
                } catch (_: Exception) {
                    latch.countDown()
                }
            }

            latch.await(3500L, TimeUnit.MILLISECONDS)
            mainHandler.post {
                try { lm.removeUpdates(listener) } catch (_: Exception) {}
            }

            var bestLocation = freshLocationRef.get()
            var isLiveFix = bestLocation != null

            if (bestLocation == null) {
                val providers = lm.getProviders(true)
                for (provider in providers) {
                    val l = try { lm.getLastKnownLocation(provider) } catch (_: SecurityException) { null } ?: continue
                    if (bestLocation == null || l.time > bestLocation!!.time) {
                        bestLocation = l
                    }
                }
            }

            if (bestLocation != null) {
                val lat = bestLocation!!.latitude
                val lng = bestLocation!!.longitude
                val acc = bestLocation!!.accuracy.toInt()
                val fixTimeStr = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
                    .format(Date(bestLocation!!.time))
                val ageMinutes = ((System.currentTimeMillis() - bestLocation!!.time).coerceAtLeast(0L)) / 60000L
                val statusLabel = if (isLiveFix || ageMinutes == 0L) {
                    "Live Fix ($fixTimeStr)"
                } else {
                    "Last Known Fix ($ageMinutes min ago at $fixTimeStr)"
                }
                "Coordinates: $lat, $lng (Accuracy: ~${acc}m)\nLocation Fix Time: $statusLabel\nGoogle Maps Link: https://maps.google.com/?q=$lat,$lng"
            } else {
                "Unavailable (Phone GPS/Location toggle is currently turned OFF)"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting location", e)
            "Unavailable (${e.javaClass.simpleName})"
        }
    }

    fun sendIntruderAlert(
        context: Context,
        reason: String,
        photoFile: File?,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        val senderEmail = PrefsManager.getSenderEmail(context)
        val appPassword = PrefsManager.getAppPassword(context)
        val receiverEmail = PrefsManager.getReceiverEmail(context)

        if (senderEmail.isEmpty() || appPassword.isEmpty()) {
            Log.w(TAG, "Email credentials not configured")
            onResult?.invoke(false, "Sender Gmail and 16-digit App Password are required.")
            return
        }

        executor.execute {
            val locationInfo = getDeviceLocationString(context)

            if (photoFile != null && photoFile.exists()) {
                try {
                    val locFile = File(photoFile.parentFile, photoFile.nameWithoutExtension + ".loc")
                    locFile.writeText(locationInfo)
                } catch (_: Exception) {}
            }

            if (!isInternetAvailable(context)) {
                Log.i(TAG, "Internet is OFF. Enabling Wi-Fi & queuing photo + location.")
                tryAutoEnableAndConnectWifi(context)
                scheduleDelayedRetry(context, 15_000L)
                onResult?.invoke(false, "Internet OFF: Auto-enabling Wi-Fi & queued to send when connected.")
                return@execute
            }

            val success = sendEmailBlocking(
                context = context,
                senderEmail = senderEmail,
                appPassword = appPassword,
                receiverEmail = receiverEmail,
                reason = reason,
                photoFile = photoFile,
                locationInfo = locationInfo,
                captureTimeMs = photoFile?.lastModified() ?: System.currentTimeMillis(),
                onResult = onResult
            )
            if (success) {
                flushPendingAlertsInternal(context, senderEmail, appPassword, receiverEmail)
            } else {
                scheduleDelayedRetry(context, 20_000L)
            }
        }
    }

    private fun scheduleDelayedRetry(context: Context, delayMs: Long) {
        val appCtx = context.applicationContext
        mainHandler.postDelayed({
            if (isInternetAvailable(appCtx)) {
                flushPendingAlerts(appCtx)
            }
        }, delayMs)
    }

    fun flushPendingAlerts(context: Context) {
        val senderEmail = PrefsManager.getSenderEmail(context)
        val appPassword = PrefsManager.getAppPassword(context)
        val receiverEmail = PrefsManager.getReceiverEmail(context)
        if (senderEmail.isEmpty() || appPassword.isEmpty()) return
        if (!isInternetAvailable(context)) return

        executor.execute {
            flushPendingAlertsInternal(context, senderEmail, appPassword, receiverEmail)
        }
    }

    private fun flushPendingAlertsInternal(
        context: Context,
        senderEmail: String,
        appPassword: String,
        receiverEmail: String
    ) {
        try {
            val dir = CameraCaptureHelper.getPendingAlertsDir(context)
            val pendingFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
                ?.sortedBy { it.lastModified() } ?: return

            for (file in pendingFiles) {
                if (!file.exists()) continue
                if (!isInternetAvailable(context)) break

                val locFile = File(file.parentFile, file.nameWithoutExtension + ".loc")
                val savedLoc = if (locFile.exists()) {
                    try { locFile.readText() } catch (_: Exception) { null }
                } else null
                val currentLoc = getDeviceLocationString(context)
                val combinedLoc = if (!savedLoc.isNullOrBlank() && savedLoc != currentLoc) {
                    "At Attempt Time:\n$savedLoc\n\nCurrent Live Location (When Online):\n$currentLoc"
                } else {
                    currentLoc
                }

                val sent = sendEmailBlocking(
                    context = context,
                    senderEmail = senderEmail,
                    appPassword = appPassword,
                    receiverEmail = receiverEmail,
                    reason = "Queued Power-Off Attempt (Sent after internet connection confirmed)",
                    photoFile = file,
                    locationInfo = combinedLoc,
                    captureTimeMs = file.lastModified(),
                    onResult = null
                )
                if (sent && locFile.exists()) {
                    try { locFile.delete() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing pending alerts", e)
        }
    }

    private fun sendEmailBlocking(
        context: Context,
        senderEmail: String,
        appPassword: String,
        receiverEmail: String,
        reason: String,
        photoFile: File?,
        locationInfo: String,
        captureTimeMs: Long,
        onResult: ((Boolean, String) -> Unit)?
    ): Boolean {
        return try {
            // Strict TLS 1.2 / 1.3 + Server Identity Verification (Prevents STARTTLS stripping on Open Wi-Fi)
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
                put("mail.smtp.ssl.checkserveridentity", "true")
                put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
                put("mail.smtp.host", "smtp.gmail.com")
                put("mail.smtp.port", "587")
                put("mail.smtp.connectiontimeout", "12000")
                put("mail.smtp.timeout", "12000")
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(senderEmail, appPassword)
                }
            })

            val timestamp = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
                .format(Date(captureTimeMs))
            val batteryPct = getBatteryPercentage(context)
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(senderEmail, "LockGuard Security"))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(receiverEmail))
                subject = "ALERT: Power-Off Attempt & Location on ${Build.MODEL}"
            }

            val textPart = MimeBodyPart().apply {
                setText(
                    buildString {
                        appendLine("LockGuard Security Alert")
                        appendLine("----------------------------------------")
                        appendLine("Event: $reason")
                        appendLine("Device: $deviceName")
                        appendLine("Attempt Time: $timestamp")
                        appendLine("Battery Level: $batteryPct%")
                        appendLine("----------------------------------------")
                        appendLine("DEVICE LOCATION:")
                        appendLine(locationInfo)
                        appendLine("----------------------------------------")
                        if (photoFile != null && photoFile.exists()) {
                            appendLine("Front camera intruder photo is attached below.")
                        } else {
                            appendLine("Note: Front camera image could not be captured.")
                        }
                    }
                )
            }

            val multipart = MimeMultipart().apply {
                addBodyPart(textPart)
                if (photoFile != null && photoFile.exists()) {
                    val attachmentPart = MimeBodyPart().apply {
                        dataHandler = DataHandler(FileDataSource(photoFile))
                        fileName = "intruder_selfie.jpg"
                    }
                    addBodyPart(attachmentPart)
                }
            }

            message.setContent(multipart)
            Transport.send(message)

            // Delete photo and .loc file only after confirmed successful send
            try {
                photoFile?.let {
                    File(it.parentFile, it.nameWithoutExtension + ".loc").delete()
                    it.delete()
                }
            } catch (_: Exception) {}

            Log.i(TAG, "Intruder alert email with location sent and cache cleared!")
            onResult?.invoke(true, "Email + Location sent successfully to $receiverEmail!")
            true
        } catch (authEx: AuthenticationFailedException) {
            // Bug 3 fix: Keep photoFile in cache (capped at 5) so evidence is not lost if password had a typo
            Log.e(TAG, "Gmail authentication failed - keeping photo in pending queue", authEx)
            onResult?.invoke(false, "Gmail login failed: Check your 16-digit App Password (photo saved in queue).")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send email (will retry)", e)
            tryAutoEnableAndConnectWifi(context)
            onResult?.invoke(false, "Email error: ${e.localizedMessage ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun getBatteryPercentage(context: Context): Int {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100) / scale else -1
        } catch (_: Exception) {
            -1
        }
    }
}
