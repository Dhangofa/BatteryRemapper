# BatteryRemapper (LSPosed Module)

BatteryRemapper is a lightweight, headless Xposed/LSPosed module designed for modern, highly customized Android environments. It recalibrates the operating system's battery reporting scale, mapping a physical battery range of **20% – 80%** directly into a displayed **0% – 100%** layer. 

By constraining your operational cycle to these healthy thresholds, this module dramatically slows down chemical aging and extends the longevity of your device's physical lithium-ion battery.

---

## 🚀 Features

* **Linear Scale Remapping:** Compresses a physical 60% battery capacity swing (20% to 80%) into a seamless, user-facing 0% to 100% display layer.
* **Dynamic Intent Interception:** Hooks deeply into the `Intent.getIntExtra` layer within the System UI, making it highly compatible with modern, heavily modified custom AOSP ROMs.
* **Smart Hysteresis Battery Saver Automation:** Automatically manages your Android Battery Saver profile based on your remapped percentages to eliminate constant state-toggling:
  * **Triggers ON:** Automatically forces Battery Saver mode **ON** the moment your remapped scale drops to **20% or below**.
  * **Triggers OFF:** Automatically forces Battery Saver mode **OFF** when your remapped scale charges past **50%**.
  * **Hysteresis Dead-Zone:** Keeps the existing power state completely stable between **21% – 50%** when unplugged to prevent power-flickering.
  * **Charging Bypass:** Instantly kills and disables Battery Saver mode the moment a charger (AC/USB/Wireless) is connected, allowing unthrottled charging performance.
* **30-Second Shutdown Countdown:** When your physical battery drops to your absolute limit of 20% (showing 0% on screen), a system-level alert dialog pops up with a 30-second countdown.
* **Smart Charger Abort:** Plugging in your charger at any point during the 30-second countdown instantly cancels the shutdown sequence and dismisses the alert dialog.
* **Pure SystemUI Execution:** Runs with zero root shell overhead (`su`) or permanent kernel-level RAM corruption by executing completely within the trusted `com.android.systemui` scope.

---

## ⚙️ LSPosed Setup Configuration

To ensure the module functions correctly, you must configure its scope properly inside your LSPosed / Vector Manager interface.

### Target Scope:
* [x] **System UI** (`com.android.systemui`)

> [!IMPORTANT]
> This module is engineered to run inside the System UI process. This ensures that your status bar, quick setting tiles, and lock screen graphics safely reflect your custom 0-100% scale, while your device's core Android system server still maintains accurate tracking of physical metrics.

---

## 📈 How the Math Works

Because the module maps a 60-point physical range into a 100-point display range, every **1% change in physical battery** translates to roughly **1.66% change on your screen**. 

| Actual Physical Battery | Displayed Status Bar Battery | Current Behavior & States |
| :--- | :--- | :--- |
| **80% and above** | `100%` | Upper charge ceiling cap |
| **65%** | `75%` | Intermediate remapped value |
| **51%** | `51%` | Battery Saver turns **OFF** if unplugged |
| **25%** | `8%` | Low battery threshold — **Battery Saver is ON** |
| **20% and below** | `0%` | Triggers the 30-second shutdown countdown |

---

## ⚠️ Drawbacks & Warnings

* **Stepped Percentage Drops:** Because of the compressed mathematical mapping, your status bar percentage will occasionally skip digits (e.g., jumping directly from 77% to 75%). This is intended, native mathematical behavior.
* **Kernel Supply Access:** Apps that pull battery data bypassing the Java framework directly from the Linux kernel file node layer (`/sys/class/power_supply/battery/capacity`) will still read the true physical percentage rather than your remapped scale.

---

## 📱 Supported Devices & Environment

* **Environment Requirement:** Functioning Zygisk environment via Magisk or KernelSU.
* **Framework Requirement:** LSPosed / Vector Framework.
* **Target Architecture:** Verified on modern **Android 16** AOSP architectures (specifically tested on custom implementations like **Evolution X** and **crDroid** builds).
* **ROM Compatibility:** Fully compatible with heavily modified status bar engines that deploy custom battery styles like Circles, Dotted Icons, Landscape pills, and Text indicators.

---

## 💬 Contact & Support

[![Telegram](https://img.shields.io/badge/Telegram-%230088cc?style=plastic&logo=telegram&logoColor=white)](https://t.me/dhangofa)
