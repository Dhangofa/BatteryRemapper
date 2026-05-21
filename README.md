# BatteryRemapper (LSPosed Module)
BatteryRemapper is a lightweight, headless Xposed/LSPosed module designed for custom Android environments. It recalibrates the operating system's battery reporting scale, mapping a physical battery range of **20% – 80%** directly into a displayed **0% – 100%** layer. 
By limiting your operational battery cycle to these thresholds, this module dramatically slows down chemical aging and extends the longevity of your device's physical lithium-ion battery.
---
## 🚀 Features
* **Linear Scale Remapping:** Compresses a physical 60% battery capacity swing (20% to 80%) into a seamless, user-facing 0% to 100% display layer.
* **Dynamic Intent Interception:** Hooks deeply into the `Intent.getIntExtra` layer within the System UI, making it highly compatible with modern, heavily modified custom AOSP ROMs.
* **30-Second Shutdown Countdown:** When your physical battery drops to 20% (showing 0% on screen), a system-level alert dialog pops up with a 30-second countdown.
* **Smart Charger Abort:** Plugging in your charger at any point during the 30-second countdown instantly cancels the countdown and dismisses the alert dialog.
* **Headless Design:** Runs completely background-driven with no app drawer icon or unnecessary battery drain.
---
## ⚙️ LSPosed Setup Configuration
To ensure the module functions correctly, you must configure its scope properly inside the LSPosed Manager app.
### Which Application to Target:
* [x] **System UI** (`com.android.systemui`)
> [!IMPORTANT]
> **DO NOT** check "System Framework" (`android`). 
> This module is intentionally scoped to the System UI process. This guarantees that your status bar, widgets, and lock screen graphics display your custom 0-100% scale, while the underlying core Android kernel still knows the actual physical battery metrics to manage safe hardware operations.
---
## 📈 How the Math Works
Because the module maps a 60-point physical range into a 100-point display range, every **1% change in physical battery** translates to roughly **1.66% change on your screen**. 

| Actual Physical Battery | Displayed Status Bar Battery | Notes |
| :--- | :--- | :--- |
| **80% and above** | `100%` | Upper charge ceiling cap |
| **65%** | `75%` | Intermediate remapped value |
| **58%** | `63%` | Intermediate remapped value |
| **25%** | `8%` | Low battery threshold |
| **20% and below** | `0%` | Triggers the 30-second shutdown countdown |

---
## ⚠️ Drawbacks & Warnings
* **Stepped Percentage Drops:** Because of the compressed mathematical mapping, your status bar percentage will occasionally skip digits (e.g., jumping directly from 77% to 75%). This is completely normal behavior.
* **Third-Party App Mismatches:** Apps that pull battery data directly from the system hardware layer or the Android kernel (such as AccuBattery, physical hardware monitors, or custom terminal scripts) will still read the true physical percentage rather than your remapped scale.
* **Bootloop Protection Failsafe:** The automatic power-off sequence relies on the system `PowerManager`. If a custom ROM strictly blocks the drawing of the countdown UI dialog, a native safety mechanism will still trigger a graceful power shutdown to protect the cell from falling below your physical 20% limit.
---
## 📱 Supported Devices & Environment
* **Root Requirement:** Magisk or KernelSU with a functioning Zygisk environment.
* **Framework Requirement:** LSPosed (Zygisk release).
* **Tested Architecture:** Designed and verified on custom AOSP architectures, specifically tested on **crDroid 12.9** running on the **Redmi Note 12 Pro 5G** platform.
* **ROM Compatibility:** Highly compatible with heavily modified status bar engines (e.g., Evolution X, crDroid, Infinity X) that deploy custom battery styles like Circles, Dotted Icons, and Text indicators.
---
## 💬 Contact & Support

[![Telegram](https://img.shields.io/badge/Telegram-%230088cc?style=plastic&logo=telegram&logoColor=white)](https://t.me/dhangofa)
