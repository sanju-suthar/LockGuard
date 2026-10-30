package com.security.lockguard

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvServiceStatus: TextView
    private lateinit var switchPowerBlock: Switch
    private lateinit var switchPhotoAlert: Switch
    private lateinit var btnCameraPerm: Button
    private lateinit var btnBatteryOpt: Button
    private lateinit var btnAccessibility: Button
    private lateinit var etSenderEmail: EditText
    private lateinit var etAppPassword: EditText
    private lateinit var etReceiverEmail: EditText
    private lateinit var btnSaveSettings: Button
    private lateinit var btnTestEmail: Button
    private lateinit var tvTestResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        switchPowerBlock = findViewById(R.id.switchPowerBlock)
        switchPhotoAlert = findViewById(R.id.switchPhotoAlert)
        btnCameraPerm = findViewById(R.id.btnCameraPerm)
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        etSenderEmail = findViewById(R.id.etSenderEmail)
        etAppPassword = findViewById(R.id.etAppPassword)
        etReceiverEmail = findViewById(R.id.etReceiverEmail)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        btnTestEmail = findViewById(R.id.btnTestEmail)
        tvTestResult = findViewById(R.id.tvTestResult)

        // Load saved preferences
        switchPowerBlock.isChecked = PrefsManager.isPowerBlockEnabled(this)
        switchPhotoAlert.isChecked = PrefsManager.isPhotoAlertEnabled(this)
        etSenderEmail.setText(PrefsManager.getSenderEmail(this))
        etAppPassword.setText(PrefsManager.getAppPassword(this))
        etReceiverEmail.setText(PrefsManager.getReceiverEmail(this))

        switchPowerBlock.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setPowerBlockEnabled(this, isChecked)
        }

        switchPhotoAlert.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setPhotoAlertEnabled(this, isChecked)
        }

        btnCameraPerm.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
            } else {
                Toast.makeText(this, "Camera permission is already granted!", Toast.LENGTH_SHORT).show()
            }
        }

        btnBatteryOpt.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Find 'Downloaded Services' -> 'LockGuard Power Blocker' and turn it ON",
                Toast.LENGTH_LONG
            ).show()
        }

        btnSaveSettings.setOnClickListener {
            saveCurrentEmailSettings()
            Toast.makeText(this, "Settings saved securely on your device!", Toast.LENGTH_SHORT).show()
        }

        btnTestEmail.setOnClickListener {
            saveCurrentEmailSettings()
            val sender = PrefsManager.getSenderEmail(this)
            val pass = PrefsManager.getAppPassword(this)
            if (sender.isEmpty() || pass.isEmpty()) {
                tvTestResult.setTextColor(Color.parseColor("#F87171"))
                tvTestResult.text = "Please enter both Sender Gmail and 16-digit App Password first."
                return@setOnClickListener
            }

            tvTestResult.setTextColor(Color.parseColor("#FCD34D"))
            tvTestResult.text = "Capturing front camera photo & sending test email..."
            btnTestEmail.isEnabled = false

            CameraCaptureHelper.captureFrontPhoto(this) { photoFile ->
                MailSender.sendIntruderAlert(
                    context = this,
                    reason = "Test Alert from LockGuard App Setup",
                    photoFile = photoFile
                ) { success, msg ->
                    runOnUiThread {
                        btnTestEmail.isEnabled = true
                        tvTestResult.setTextColor(
                            if (success) Color.parseColor("#34D399") else Color.parseColor("#F87171")
                        )
                        tvTestResult.text = msg
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
    }

    private fun saveCurrentEmailSettings() {
        PrefsManager.saveEmailConfig(
            context = this,
            senderEmail = etSenderEmail.text.toString(),
            appPassword = etAppPassword.text.toString(),
            receiverEmail = etReceiverEmail.text.toString()
        )
    }

    private fun updatePermissionStates() {
        val camGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        btnCameraPerm.text = if (camGranted) {
            "Camera Permission: GRANTED ✓"
        } else {
            "Grant Front Camera Permission"
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
        btnBatteryOpt.text = if (ignoringBattery) {
            "24/7 Background Run: ALLOWED ✓"
        } else {
            "Allow 24/7 Background Run (No Battery Kill)"
        }

        val accEnabled = isAccessibilityServiceEnabled(this, LockGuardService::class.java)
        if (accEnabled) {
            tvServiceStatus.text = "Status: ACTIVE & PROTECTING LOCKSCREEN ✓"
            tvServiceStatus.setTextColor(Color.parseColor("#34D399"))
            btnAccessibility.text = "LockGuard Accessibility Service: ON ✓"
        } else {
            tvServiceStatus.text = "Status: INACTIVE (Turn on Accessibility Service)"
            tvServiceStatus.setTextColor(Color.parseColor("#F87171"))
            btnAccessibility.text = "Enable LockGuard Accessibility Service"
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            } else {
                Toast.makeText(this, "Already allowed to run 24/7 in background!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, serviceClass)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStates()
    }
}
