# 🛡️ LockGuard — Anti-Theft Lockscreen Protector

![GitHub release (latest by date)](https://img.shields.io/github/v/release/sanju-suthar/LockGuard?style=flat-square)
![Platform](https://img.shields.io/badge/Platform-Android-brightgreen?style=flat-square)
![Build](https://img.shields.io/badge/Build-GitHub%20Actions-blue?style=flat-square)
![License](https://img.shields.io/github/license/sanju-suthar/LockGuard?style=flat-square)
![Issues](https://img.shields.io/github/issues/sanju-suthar/LockGuard?style=flat-square)

**LockGuard** is an advanced Android anti-theft utility designed to stop thieves from powering off or restarting your device while it is locked. The moment someone tries to open the Power Menu on the lock screen, LockGuard blocks it within milliseconds, silently captures a front-camera photo, grabs the exact GPS location, auto-connects to Wi-Fi if offline, and emails the complete report to you.

---

## 📥 Download Latest APK

Grab the ready-to-install APK built directly via GitHub Actions:

👉 **[Download Latest APK Here](https://github.com/sanju-suthar/LockGuard/releases/latest)**

> **⚠️ Play Protect & Installation Note:** Because LockGuard uses `AccessibilityService` to block the lockscreen power menu and `targetSdk 28` to allow silent background Wi-Fi toggling, Android Play Protect may show a prompt during installation. Tap **"More details" -> "Install anyway"** to proceed.

---

## 🚀 Key Features & Technical Architecture

* **🚫 Smart Power Menu Blocker (Zero False Triggers):** 
  Uses `AccessibilityService` (`TYPE_WINDOW_STATE_CHANGED` & `onKeyEvent`) to strictly detect genuine Android System Shutdown/GlobalActions dialogs (`GlobalActions`, `OplusGlobalActions`, `ColorGlobalActions`). Completely ignores lockscreen SMS/WhatsApp notifications (`notification_stack_scroller`) to guarantee zero false alarms.
* **📸 Silent Intruder Selfie (`CameraX` + `TYPE_ACCESSIBILITY_OVERLAY`):** 
  Uses a 1x1 pixel invisible overlay on the lock screen to capture a front-camera photo of the intruder without any camera preview or shutter sound.
* **📍 Live GPS Location + Dual Offline Location Tracking (`.loc` Backup):** 
  Attaches exact GPS Coordinates, Accuracy (in meters), and a clickable **Google Maps Link** (`https://maps.google.com/?q=lat,lng`) to the alert email. If offline during the attempt, it caches the exact `.loc` coordinates and later sends **both** the **Attempt-Time Location** and **Current Live Location** once reconnected!
* **📶 Auto Wi-Fi Enable & Open Wi-Fi Connect:** 
  If mobile data and Wi-Fi are turned OFF, LockGuard automatically switches Wi-Fi ON in the background (`targetSdk 28`) and connects to known networks or the strongest nearby **Open (Password-free) Wi-Fi** to transmit the intruder alert.
* **📧 Offline Photo Queue & Auto-Sync (`JavaMail SMTP` + `NetworkCallback`):** 
  Queues up to the latest 5 intruder photos (`~200 KB` max via `enforceCacheLimit()`) when offline and automatically emails & clears the cache the moment internet connectivity is restored.
* **👻 Stealth 24/7 Background Operation (`BOOT_COMPLETED` / Direct Boot):** 
  Runs silently with zero persistent notifications and auto-starts on reboot even before the device is unlocked.

---

## ⚙️ Complete Setup Guide

### Step 1: Generate a Gmail App Password (For Email Alerts)
*(Note: Google does not allow regular Gmail passwords for background SMTP emails. You must generate a 16-character App Password).*

1. Open your [Google Account Security Settings](https://myaccount.google.com/security) and ensure **2-Step Verification** is **ON**.
2. Go to the **[Google App Passwords Page](https://myaccount.google.com/apppasswords)**.
3. Enter an app name (e.g., `LockGuard`) and tap **Create**.
4. Copy the **16-letter password** (without spaces) and paste it into the LockGuard app's SMTP Password field.

### Step 2: Grant Required Android Permissions
1. **Camera, Location & Wi-Fi:** 
   * Open LockGuard, allow **Camera** access, and tap **"Grant Exact Location & Wi-Fi Permission"**.
   * Set Location permission to **"Allow all the time"** so LockGuard can fetch GPS coordinates and scan for open Wi-Fi networks while the screen is locked.
   * Enable the switches for **"Send Exact GPS Location in Email"** and **"Auto-Enable Wi-Fi & Connect if Offline"**.
2. **Accessibility Service:** 
   * Go to `Settings -> Accessibility -> Downloaded Apps -> LockGuard` and turn it **ON**.
   * *Android 13+ Users:* If restricted, open `Settings -> Apps -> LockGuard`, tap the **Three Dots (⋮)** at the top right, and select **"Allow restricted settings"**.
3. **OEM Background Optimization (Crucial for Realme UI / MIUI / ColorOS):** 
   * Go to `App Info -> Battery Usage` and enable **"Allow background activity"** & **"Allow auto-launch"**.

---

## ⚠️ Known Hardware Limitation

* **On-Screen Power Menu vs. Hardware Force Reboot:** LockGuard blocks the on-screen software Power Menu 100% of the time and captures the intruder's photo and GPS location. However, if someone holds `Power + Volume Up` for 10–12 seconds, the motherboard triggers a hardware-level reset which no Android software can intercept. To counter this, LockGuard supports **Direct Boot**, ensuring protection restarts immediately as soon as the phone boots back to the lock screen.

---

## 🐛 Testing, Bug Reports & Contributions

* **Found a Bug or Device Issue?** Open an issue in the **[Issues Tab](https://github.com/sanju-suthar/LockGuard/issues)** with your **Phone Model** and **Android Version**.
* **Want to Contribute?** Feel free to fork the repository and submit a **Pull Request (PR)**.

---

## 👤 Author

**Developed by Sanjay Suthar**
* GitHub: [@sanju-suthar](https://github.com/sanju-suthar)

⭐ *If you find this anti-theft tool helpful, please give this repository a **Star**!*
