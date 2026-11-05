# 🛡️ LockGuard — Anti-Theft Lockscreen Protector

![GitHub release (latest by date)](https://img.shields.io/github/v/release/sanju-suthar/LockGuard?style=flat-square&cacheSeconds=60)
![Platform](https://img.shields.io/badge/Platform-Android-brightgreen?style=flat-square)
![Build](https://img.shields.io/badge/Build-GitHub%20Actions-blue?style=flat-square)
![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)
![Issues](https://img.shields.io/github/issues/sanju-suthar/LockGuard?style=flat-square)

**LockGuard** is an Android anti-theft utility designed to prevent unauthorized users from powering off or restarting your device while it is locked. When the system Power Menu is triggered on the lock screen, LockGuard dismisses the dialog, captures a silent front-camera photo, retrieves GPS coordinates with a Google Maps link, auto-enables Wi-Fi if offline, and sends an encrypted email alert.

---

## 📥 Download Latest APK

Download the latest signed release APK from the GitHub Releases page or build artifacts:

👉 **[Download Latest Release APK](https://github.com/sanju-suthar/LockGuard/releases/latest)**

> **⚠️ Play Protect & Installation Note:** Because LockGuard uses `AccessibilityService` to detect the lockscreen power menu and `targetSdk 28` to allow background Wi-Fi toggling on Android 10/11, Google Play Protect may display a warning during sideloading. Temporarily pause Play Protect in Play Store settings or tap **"More details" -> "Install anyway"** to proceed.

---

## 🚀 Key Features & Technical Architecture

* **🚫 Smart Power Menu Blocker:** Uses `AccessibilityService` (`TYPE_WINDOW_STATE_CHANGED` & `onKeyEvent`) restricted to system packages (`android`, `com.android.systemui`, ColorOS/Realme UI global actions) to detect genuine Shutdown/Restart dialogs while ignoring lockscreen notification panels (`notification_stack_scroller`).
* **📸 Silent Intruder Selfie (Camera2 API):** Uses Android's native `Camera2` API with a 1×1 pixel invisible `TYPE_ACCESSIBILITY_OVERLAY` window and a `750ms` auto-exposure warm-up to capture a front-camera photo without displaying a preview or shutter sound.
* **📍 Live GPS Location + Offline `.loc` Backup:** Attaches GPS/Network coordinates, accuracy in meters, the exact timestamp/age of the location fix (*Live Fix vs. Last Known Fix*), and a clickable Google Maps link (`https://maps.google.com/?q=lat,lng`). If offline during the attempt, it saves a companion `.loc` file and emails both the **Attempt-Time Location** and **Current Live Location** once reconnected.
* **📶 Auto Wi-Fi Enable & Reconnect:** If both Mobile Data and Wi-Fi are turned off when a lockscreen power-off attempt occurs, LockGuard automatically enables Wi-Fi in the background and connects to saved networks or available open Wi-Fi networks so the alert can be delivered.
* **🔐 Hardware-Backed Security (AES-GCM & Strict TLS):**
  * **Android Keystore Encryption:** Your 16-digit Gmail App Password is encrypted locally using a `256-bit AES/GCM/NoPadding` key stored in the Android Keystore inside `DeviceProtectedStorage`.
  * **Strict SMTP TLS 1.2/1.3:** Enforces `mail.smtp.starttls.required=true` and `mail.smtp.ssl.checkserveridentity=true` over `smtp.gmail.com:587` to protect credentials and attachments against downgrade or man-in-the-middle (MITM) attacks on untrusted networks.
* **📦 Offline Queue & Cache Cap (Max 5 Photos):** Queues up to 5 recent intruder photos (`~200 KB` max) when offline and automatically sends and deletes them via `ConnectivityManager.NetworkCallback` (with automatic delayed retry) once internet access returns.
* **⚡ 24/7 Background Operation & Direct Boot:** Runs silently as a bound system Accessibility Service without persistent status bar notifications and registers for `LOCKED_BOOT_COMPLETED` so protection resumes immediately after a reboot before the device is unlocked.

---

## 🛠️ Complete Setup Guide

### Step 1: Generate a 16-Digit Gmail App Password
1. Enable [2-Step Verification](https://myaccount.google.com/security) on your Google Account.
2. Visit [Google App Passwords](https://myaccount.google.com/apppasswords).
3. Enter `LockGuard` as the app name and tap **Create**.
4. Copy the **16-character App Password** and enter it inside the LockGuard app along with your Sender and Receiver Gmail addresses, then tap **Save Settings**.

### Step 2: Grant Required Permissions
1. **Front Camera Permission:** Tap **"Grant Front Camera Permission"** and select **Allow**.
2. **Exact Location & Wi-Fi Permission:** Tap **"Grant Exact Location & Wi-Fi Permission"** and select **"Allow all the time"** in App Info so GPS lookup and Wi-Fi scanning work while the screen is locked.
3. **24/7 Background Run:** Tap **"Allow 24/7 Background Run"** to exempt LockGuard from battery optimizations.
   * *Realme UI / ColorOS / OPPO Tip:* Open `App Info -> Battery Usage` and enable **Allow background activity** and **Allow auto-launch**.
4. **Accessibility Service:** Tap **"Enable LockGuard Accessibility Service"**, navigate to `Downloaded Services (Installed Apps) -> LockGuard Power Blocker`, and turn it **ON**.
5. Tap **"Test Camera, GPS Location & Send Email"** to verify your configuration.

---

## ⚠️ Known Limitations

* **Hardware Force Reboot:** Software can dismiss the on-screen Power Off / Restart menu, but holding `Power + Volume Up` for 10–12 seconds triggers a hardware-level reset built into the device motherboard that no Android software can block. LockGuard mitigates this using Direct Boot (`directBootAware="true"`) to restart protection as soon as the phone boots back to the lock screen.
* **OEM Variations:** Power menu detection relies on system window classes and view structures, which can vary across custom Android skins (Realme UI, ColorOS, MIUI, OneUI). Test on your specific device after installation.

---

## 👨‍💻 Developer

**Developed by Sanjay Suthar**
* **GitHub Profile:** [@sanju-suthar](https://github.com/sanju-suthar)
* **Project Repository:** [https://github.com/sanju-suthar/LockGuard](https://github.com/sanju-suthar/LockGuard)

⭐ *If you find LockGuard useful, please consider giving this repository a **Star**!*
