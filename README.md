# 🛡️ LockGuard — Anti-Theft Lockscreen Protector

![GitHub release (latest by date)](https://img.shields.io/github/v/release/sanju-suthar/LockGuard?style=flat-square)
![Platform](https://img.shields.io/badge/Platform-Android-brightgreen?style=flat-square)
![Build](https://img.shields.io/badge/Build-GitHub%20Actions-blue?style=flat-square)
![Issues](https://img.shields.io/github/issues/sanju-suthar/LockGuard?style=flat-square)

**LockGuard** is a specialized Android anti-theft utility designed to stop thieves from powering off or restarting your device while it is locked. The moment someone tries to open the Power Menu on the lock screen, LockGuard blocks it immediately, silently snaps a front-camera photo, and emails it to you with device details.

---

## 📥 Download Latest APK

Grab the ready-to-install APK built directly via GitHub Actions:

👉 **[Download Latest APK Here](https://github.com/sanju-suthar/LockGuard/releases/latest)**

> **⚠️ Play Protect Warning:** Because LockGuard uses `AccessibilityService` to monitor the lockscreen power menu and is distributed outside the Google Play Store, Android Play Protect may flag it as an unrecognized app. Tap **"More details" -> "Install anyway"** to install.

---

## 🚀 Key Features & Technical Architecture

* **🚫 Smart Power Menu Blocker (Zero False Triggers):** 
  Uses `AccessibilityService` (`TYPE_WINDOW_STATE_CHANGED` & `onKeyEvent`) to strictly detect genuine Android System Shutdown/GlobalActions dialogs (`GlobalActions`, `OplusGlobalActions`, `ColorGlobalActions`). Completely ignores lockscreen SMS/WhatsApp notifications (`notification_stack_scroller`) to guarantee zero false alarms.
* **📸 Silent Intruder Selfie (`CameraX` + `TYPE_ACCESSIBILITY_OVERLAY`):** 
  Uses a 1x1 pixel invisible overlay on the lock screen to capture a front-camera photo of the intruder without any camera preview or shutter sound.
* **📧 Instant Email Alerts + Offline Auto-Sync (`JavaMail SMTP` + `NetworkCallback`):** 
  Immediately emails the intruder's photo, exact timestamp, and battery level. If the device is offline, photos are queued locally (capped at the latest 5 photos / ~200 KB max via `enforceCacheLimit()`) and automatically emailed & deleted from cache the moment Wi-Fi or Mobile Data reconnects!
* **👻 Stealth 24/7 Background Operation (`BOOT_COMPLETED` / Direct Boot):** 
  Runs silently with zero persistent notifications and auto-starts on reboot even before the device is unlocked.


---

## ⚙️ Complete Setup Guide

### Step 1: Generate a Gmail App Password (For Email Alerts)
*(Note: Google does not allow regular Gmail passwords for background SMTP emails. You must generate a 16-character App Password).*

1. Open your [Google Account Security Settings](https://myaccount.google.com/security).
2. Make sure **2-Step Verification** is turned **ON**.
3. Go directly to the **[Google App Passwords Page](https://myaccount.google.com/apppasswords)** (or search *"App passwords"* in the top search bar of your Google Account).
4. Enter an app name (e.g., `LockGuard`) and tap **Create**.
5. Copy the **16-letter password** shown on your screen (ignore the spaces) and paste it into the LockGuard app's SMTP Password field.

### Step 2: Grant Required Android Permissions
1. **Camera & Internet:** Open LockGuard and allow Camera access.
2. **Accessibility Service:** Go to `Settings -> Accessibility -> Downloaded Apps -> LockGuard` and turn it **ON**.
   * *Android 13+ Users:* If the toggle is greyed out ("Restricted Setting"), open Phone `Settings -> Apps -> LockGuard`, tap the **Three Dots (⋮)** at the top right, and select **"Allow restricted settings"**. Now go back and enable Accessibility.
3. **OEM Background Optimization (Crucial for Realme UI / MIUI / ColorOS):** 
   * Go to `App Info -> Battery Usage` and enable **"Allow background activity"** & **"Allow auto-launch"**.
   * Lock the app in your Recent Apps tray so the system never kills it.

---

## ⚠️ Known Hardware Limitation

* **On-Screen Power Menu vs. Hardware Force Reboot:** LockGuard blocks the on-screen software Power Menu 100% of the time and captures the intruder's photo. However, if someone holds `Power + Volume Up` for 10–12 seconds, the motherboard triggers a hardware-level reset which no Android software can intercept. To counter this, LockGuard supports **Direct Boot**, ensuring the protection restarts immediately as soon as the phone boots back to the lock screen.

---

## 🐛 Testing, Bug Reports & Contributions

Since different Android skins (Realme UI, OneUI, MIUI, Stock Android) handle Power Menu dialogs differently, community testing is highly appreciated!

* **Power Menu Not Blocking on Your Phone?** Open an issue in the **[Issues Tab](https://github.com/sanju-suthar/LockGuard/issues)** with your **Phone Model**, **Android Version**, and a screenshot of your Power Menu.
* **Want to Improve the Code?** Feel free to fork the repository and submit a **Pull Request (PR)**.

---

## 👤 Author

**Sanjay Suthar**
* GitHub: [@sanju-suthar](https://github.com/sanju-suthar)

⭐ *If you find this anti-theft tool helpful, please give this repository a **Star**!*
