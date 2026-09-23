# BatteryRemapper (LSPosed Module)
<div align="center">
 <p align="center">
  <img src="https://raw.githubusercontent.com/Dhangofa/BatteryRemapper/refs/heads/main/fastlane/metadata/android/en-US/images/featureGraphic.png" alt="BatteryRemapper Banner"  width="700" max-width="100%"><br>
 </p>
 
[![GitHub Release](https://img.shields.io/github/v/release/Dhangofa/BatteryRemapper?label=GitHub%20Release&logo=github&color=blue)](https://github.com/Dhangofa/BatteryRemapper/releases/latest)
[![LSPosed Repository](https://img.shields.io/badge/LSPosed%20Repo-Download-8A2BE2?logo=android&logoColor=white)](https://modules.lsposed.org/module/com.github.dhangofa.batteryremapper/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B%20(API%2029%2B)-brightgreen.svg)](https://developer.android.com)
![Framework](https://img.shields.io/badge/Framework-LSPosed%20%2F%20Xposed-blue.svg)
[![Telegram Chat](https://img.shields.io/badge/Telegram-Community%20Chat-%230088cc?logo=telegram&logoColor=white)](https://t.me/dhangofas_projects_chat)

</div>

**BatteryRemapper** is a modern Xposed/LSPosed module paired with an intuitive Material management application. It recalibrates Android's battery reporting scale, mapping a customizable physical battery window (by default **20% – 80%**) into a full **0% – 100%** display layer across your status bar, quick settings, and lock screen.

By restricting daily operational usage to these optimal chemical thresholds, BatteryRemapper significantly minimizes lithium-ion degradation, prevents high-voltage stress, and dramatically extends the lifespan of your device's non-removable battery.

---

## 🚀 Features

* **Interactive Material Manager:** Clean, responsive UI with full Material 3 dynamic styling, system bar edge-to-edge support, and automatic dark/light theme integration.
* **Customizable Physical Range:** Adjust your physical charge ceiling and floor using an interactive dual-thumb range slider (e.g., 20% – 80%, 15% – 85%, or any custom bounds).
* **Live Hook Detection:** Real-time bidirectional IPC handshake that probes System UI to immediately indicate whether the module hook is **Active**, **Inactive**, or **Checking**.
* **Linear Scale Remapping:** Smoothly translates physical battery percentage to your configured scale across the status bar, notification shade, and lock screen.
* **Smart Hysteresis Battery Saver Automation:**
  * **Triggers ON:** Automatically enables Battery Saver mode when your remapped percentage reaches 20% or below.
  * **Triggers OFF:** Automatically turns Battery Saver mode OFF once remapped battery charges past 50%.
  * **Hysteresis Dead-Zone:** Maintains existing state between 21% – 50% when unplugged to prevent battery state flickering.
  * **Charging Bypass:** Instantly disengages Battery Saver upon connecting a charger (AC, USB, or Wireless) for maximum charging performance.
  * **Toggle Control:** Can be enabled or disabled on demand from the app.
* **Automated Low-Battery Shutdown Protection:**
  * Optional safety sequence that initiates a system-level 30-second countdown when reaching your chosen trigger level (defaulting to 0% remapped).
  * **Smart Charger Abort:** Plugging in a charger instantly aborts the shutdown sequence and dismisses the alert.
  * **Dismissal Safeguard:** Dismissing the alert dialog temporarily suppresses repeated countdowns for the remainder of the discharge cycle.
* **One-Click System UI Restart:** Dedicated root-enabled action (`su -c killall com.android.systemui`) with confirmation dialog to instantly apply changes without a full device reboot.
* **Seamless Shared IPC (`SettingsProvider`):** Rootless, real-time configuration sharing between the manager app and the System UI hook via an internal Content Provider.
* **About & Credits Hub:** Dedicated About screen with dynamic version tracking, repository links, license details, and developer/contributor credits.

---

## ⚙️ LSPosed Setup Configuration

To ensure the module functions correctly, configure its target scope inside the **LSPosed Manager** (or compatible Xposed framework interface).

### Target Scope:
* [x] **System UI** (`com.android.systemui`)

> [!IMPORTANT]
> The module is specifically scoped to run inside `com.android.systemui`. This ensures your status bar, quick setting tiles, and lock screen graphics reflect your custom 0–100% scale while the underlying Android system server and kernel continue to track raw physical metrics accurately.

---

## 📈 How the Math Works

The display percentage is dynamically calculated from the physical percentage using linear normalization:

$$\text{Display \%} = \text{round}\left( \frac{\text{Physical \%} - \text{Min}}{\text{Max} - \text{Min}} \times 100 \right)$$

### Default Mapping Example (Physical 20% – 80% $\rightarrow$ Display 0% – 100%):

| Actual Physical Battery | Displayed Status Bar Battery | System Behavior & States |
| :--- | :--- | :--- |
| **80% and above** | `100%` | Upper charge ceiling cap |
| **65%** | `75%` | Intermediate remapped value |
| **51%** | `51%` | Battery Saver turns **OFF** if unplugged |
| **35%** | `25%` | Normal operational range |
| **25%** | `8%` | Low battery threshold — **Battery Saver is ON** |
| **20% and below** | `0%` | Lower charge floor — triggers shutdown countdown (if enabled) |

---

## ⚠️ Notes & Considerations

* **Stepped Percentage Drops:** Due to mathematical scaling of a compressed physical range (e.g., 60 points mapped to 100 points), the status bar percentage will occasionally step by 2% (e.g., jumping from 77% to 75%). This is natural linear rounding behavior.
* **Direct Kernel Node Reads:** Hardware monitor applications or terminal commands that bypass the Android framework to read raw kernel nodes directly (e.g., `/sys/class/power_supply/battery/capacity`) will reflect the raw physical percentage.

---

## 📱 Supported Devices & Environment

* **Android Version:** Android 10+ (API 29+).
* **Root & Framework:** Magisk or KernelSU/Apatch with Zygisk, and LSPosed (or compatible modern Xposed framework).
* **Root Permission (Optional):** Required only for the in-app "Restart System UI" quick action; the remapping and automation hooks themselves operate root-free inside System UI.
* **ROM Compatibility:**
  * **AOSP & Custom ROMs:** Pixel, LineageOS, crDroid, Evolution X, Infinity X, Axion AOSP, Motorola, Sony, etc.
  * **OEM Skins:** Xiaomi HyperOS / MIUI, Samsung One UI, OnePlus/OPPO/Realme OxygenOS & ColorOS, Vivo/iQOO OriginOS & FuntouchOS.
  * Supports status bar customizations including circles, dotted icons, landscape pills, and text-only battery styles.

---

## 📥 Download & Installation

You can get the latest pre-compiled release from either source:

* 📦 **GitHub Releases:** [Download Latest APK](https://github.com/Dhangofa/BatteryRemapper/releases/latest)
* 🧩 **LSPosed Module Repository:** [BatteryRemapper Module Page](https://modules.lsposed.org/module/com.github.dhangofa.batteryremapper/)

---

## 👥 Credits

* **Developer:** [Dhangofa](https://github.com/Dhangofa)
* **Contributor:** [Rillwyn](https://github.com/Rillwyn)

---

## 💬 Community & Support

* **Telegram Discussion Group:** [BatteryRemapper Chat](https://t.me/dhangofas_projects_chat)
* **Developer Telegram:** [@dhangofa](https://t.me/dhangofa)
* **GitHub Issues:** [BatteryRemapper Issues](https://github.com/Dhangofa/BatteryRemapper/issues)

---

## 📄 License

This project is open-source under the terms of the [MIT License](LICENSE).
