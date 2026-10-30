package com.security.lockguard

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
            try {
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

                val timestamp = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(Date())
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
                            appendLine("Time: $timestamp")
                            appendLine("Battery Level: $batteryPct%")
                            appendLine("----------------------------------------")
                            if (photoFile != null && photoFile.exists()) {
                                appendLine("Front camera intruder photo is attached below.")
                            } else {
                                appendLine("Note: Front camera image could not be captured (check Camera permission).")
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

                try {
                    photoFile?.delete()
                } catch (_: Exception) {}

                Log.i(TAG, "Intruder alert email sent successfully!")
                onResult?.invoke(true, "Email sent successfully to $receiverEmail!")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send email", e)
                onResult?.invoke(false, "Email error: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
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
