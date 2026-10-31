package com.security.lockguard

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.Executors
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
            Log.w(TAG, "Email credentials not configured; deleting temporary photo")
            try { photoFile?.delete() } catch (_: Exception) {}
            onResult?.invoke(false, "Sender Gmail and 16-digit App Password are required.")
            return
        }

        // If internet is currently OFF, keep photo in pending_alerts (max 5 files) to auto-send when internet returns
        if (!isInternetAvailable(context)) {
            Log.i(TAG, "Internet is OFF. Photo queued in pending_alerts (max 5) to send when online.")
            onResult?.invoke(false, "No internet connection right now. Queued to send when online.")
            return
        }

        executor.execute {
            val success = sendEmailBlocking(
                context = context,
                senderEmail = senderEmail,
                appPassword = appPassword,
                receiverEmail = receiverEmail,
                reason = reason,
                photoFile = photoFile,
                captureTimeMs = photoFile?.lastModified() ?: System.currentTimeMillis(),
                onResult = onResult
            )
            if (success) {
                // Also flush any older pending offline photos while we have internet
                flushPendingAlertsInternal(context, senderEmail, appPassword, receiverEmail)
            }
        }
    }

    /**
     * Called automatically when internet connection is restored.
     * Sends all queued offline intruder photos and deletes them from cache.
     */
    fun flushPendingAlerts(context: Context) {
        val senderEmail = PrefsManager.getSenderEmail(context)
        val appPassword = PrefsManager.getAppPassword(context)
        val receiverEmail = PrefsManager.getReceiverEmail(context)
        if (senderEmail.isEmpty() || appPassword.isEmpty()) {
            // Clear cache if user hasn't set up email
            val dir = CameraCaptureHelper.getPendingAlertsDir(context)
            dir.listFiles()?.forEach { try { it.delete() } catch (_: Exception) {} }
            return
        }
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
                sendEmailBlocking(
                    context = context,
                    senderEmail = senderEmail,
                    appPassword = appPassword,
                    receiverEmail = receiverEmail,
                    reason = "Offline Power-Off Attempt (Sent automatically after internet restored)",
                    photoFile = file,
                    captureTimeMs = file.lastModified(),
                    onResult = null
                )
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
        captureTimeMs: Long,
        onResult: ((Boolean, String) -> Unit)?
    ): Boolean {
        return try {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
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
                subject = "ALERT: Power-Off Attempt Blocked on ${Build.MODEL}"
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

            // Delete photo from cache immediately after sending
            try { photoFile?.delete() } catch (_: Exception) {}

            Log.i(TAG, "Intruder alert email sent and cache cleared!")
            onResult?.invoke(true, "Email sent successfully to $receiverEmail!")
            true
        } catch (authEx: AuthenticationFailedException) {
            // Wrong password - delete file so cache doesn't build up
            try { photoFile?.delete() } catch (_: Exception) {}
            onResult?.invoke(false, "Gmail login failed: Check your 16-digit App Password.")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send email (will retry when internet is stable)", e)
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
