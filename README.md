# 🛡️ LockGuard — Anti-Theft Lockscreen Protector

![GitHub release (latest by date)](https://img.shields.io/github/v/release/sanju-suthar/LockGuard?style=flat-square&cacheSeconds=60)
![Platform](https://img.shields.io/badge/Platform-Android-brightgreen?style=flat-square)
![Build](https://img.shields.io/badge/Build-GitHub%20Actions-blue?style=flat-square)
![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)
![Issues](https://img.shields.io/github/issues/sanju-suthar/LockGuard?style=flat-square)

**LockGuard** is an Android anti-theft utility designed to protect your locked device from unauthorized power-offs and unlock attempts. Whenever someone tries to open the Power Menu on the lock screen or enters an incorrect PIN/Pattern/Password, LockGuard silently captures a front-camera photo, retrieves exact GPS coordinates with a Google Maps link, auto-enables Wi-Fi if offline, and sends an encrypted email alert.

---

## 📥 Download Latest APK

Download the latest signed release APK from the GitHub Releases page:

👉 **[Download Latest Release APK](https://github.com/sanju-suthar/LockGuard/releases/latest)**

> **⚠️ Play Protect & Installation Note:** Because LockGuard uses `AccessibilityService`, `DeviceAdminReceiver`, and `targetSdk 28` to allow background Wi-Fi toggling on Android 10/11, Google Play Protect may display a warning during sideloading. Tap **"More details" -> "Install anyway"** to proceed.

---

## 🚀 Key Features & Technical Architecture

* **🚫 Smart Power Menu Blocker:** Uses `AccessibilityService` (`TYPE_WINDOW_STATE_CHANGED` & `onKeyEvent`) restricted to system packages (`android`, `com.android.systemui`, ColorOS/Realme UI global actions) to dismiss Shutdown/Restart dialogs on the lock screen while ignoring notification panels (`notification_stack_scroller`).
* **🔢 Wrong PIN / Pattern / Password Detector (`LockGuardAdminReceiver`):** Uses Android's `DeviceAdminReceiver` (`<watch-login />` / `onPasswordFailed`) to capture an intruder photo and send an `"Incorrect PIN / Pattern / Password"` alert email after **2 failed attempts** (and on any failed attempt following the 30-second lockout timer). Completely ignores **Fingerprint** and **Face Unlock** failures.
* **🛡️ Uninstall Protection:** Active Device Admin privileges prevent unauthorized users from uninstalling LockGuard without deactivating admin access first.
* **📸 Silent Intruder Selfie (Camera2 API):** Uses Android's native `Camera2` API with a 1×1 pixel invisible `TYPE_ACCESSIBILITY_OVERLAY` window and a `750ms` auto-exposure warm-up to capture a front-camera photo without preview or shutter sound.
* **📍 Live GPS Location + Offline `.loc` Backup:** Attaches GPS/Network coordinates, accuracy in meters, the exact timestamp/age of the location fix (*Live Fix vs. Last Known Fix*), and a clickable Google Maps link (`https://maps.google.com/?q=lat,lng`). If offline during the attempt, it saves a companion `.loc` file and emails both the **Attempt-Time Location** and **Current Live Location** once reconnected.
* **📶 Auto Wi-Fi Enable & Reconnect:** If both Mobile Data and Wi-Fi are turned off during an incident, LockGuard automatically enables Wi-Fi in the background and connects to saved networks or available open Wi-Fi networks so the alert can be delivered.
* **🔐 Hardware-Backed Security (AES-GCM & Strict TLS):**
  * **Android Keystore Encryption:** Your 16-digit Gmail App Password is encrypted locally using a `256-bit AES/GCM/NoPadding` key stored in the Android Keystore inside `DeviceProtectedStorage`.
  * **Strict SMTP TLS 1.2/1.3:** Enforces `mail.smtp.starttls.required=true` and `mail.smtp.ssl.checkserveridentity=true` over `smtp.gmail.com:587` to prevent MITM attacks on untrusted networks.
* **📦 Offline Queue & Cache Cap (Max 5 Photos):** Queues up to 5 recent intruder photos (`~200 KB` max) when offline and automatically emails and deletes them via `ConnectivityManager.NetworkCallback` once internet access returns.
* **⚡ 24/7 Background Operation & Direct Boot:** Runs silently without persistent status bar notifications and registers for `LOCKED_BOOT_COMPLETED` so protection resumes immediately after a reboot before the device is unlocked.

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
3. **Wrong PIN/Pattern Detector (Device Admin):** Tap **"Enable Wrong PIN/Pattern Detector (Admin)"** and tap **Activate** on the system prompt.
4. **24/7 Background Run:** Tap **"Allow 24/7 Background Run"** to exempt LockGuard from battery optimizations.
   * *Realme UI / ColorOS / OPPO Tip:* Open `App Info -> Battery Usage` and enable **Allow background activity** and **Allow auto-launch**.
5. **Accessibility Service:** Tap **"Enable LockGuard Accessibility Service"**, navigate to `Downloaded Services (Installed Apps) -> LockGuard Power Blocker`, and turn it **ON**.
6. Tap **"Test Camera, GPS Location & Send Email"** to verify your configuration.

---

## ⚠️ Known Limitations

* **Hardware Force Reboot:** Software can dismiss the on-screen Power Off / Restart menu, but holding `Power + Volume Up` for 10–12 seconds triggers a hardware-level reset built into the device motherboard that no Android software can block. LockGuard mitigates this using Direct Boot (`directBootAware="true"`) to restart protection as soon as the phone boots back to the lock screen.
* **How to Uninstall (When Needed):** Because Device Admin is enabled for Wrong PIN detection, you must first open `Settings -> Security -> Device Admin Apps`, turn off **LockGuard**, and then uninstall the app normally.

---

## 👨‍💻 Developer

**Developed by Sanjay Suthar**
* **GitHub Profile:** [@sanju-suthar](https://github.com/sanju-suthar)
* **Project Repository:** [https://github.com/sanju-suthar/LockGuard](https://github.com/sanju-suthar/LockGuard)

⭐ *If you find LockGuard useful, please consider giving this repository a **Star**!*
