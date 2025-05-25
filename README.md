# 📱 SecureSense: Real-Time Android Privacy Event Monitoring
SecureSense is an Android application developed for a final-year Computer Science project. It monitors sensitive permission usage (microphone, camera, location) on Android devices in real time. By leveraging **Shizuku** and **root access**, the app reads logcat entries to provide users with immediate privacy event alerts.

## 🌟 Key Features

- **Real-Time Monitoring**
Detects sensitive permission events like microphone, camera, and location access in real time.

- **System-Level Integration**
Uses Shizuku and root access to bypass standard Android limitations and access protected logs.

- **Notification Alerts**
Alerts users when sensitive permissions are used by applications.

- **Optimized for Android 14**
Fully tested on Android 14, considering its heightened security restrictions and limited system access.

## 🚀 Getting Started

> [!WARNING]
> **Root Access and Shizuku Required**

### 1️⃣ Prerequisites

- Android 14 device
- Shizuku app installed and configured
- Rooted device for complete logcat access

### 2️⃣ Installation

1. Clone the repository:
```git clone https://github.com/YourUsername/SecureSense.git```
2. Open in Android Studio:
```
cd SecureSense
open -a "Android Studio" .
```
3. Build and install the APK on your rooted device.

## 🛠️ Usage

1. Start the SecureSense app.
2. Grant root permissions and Shizuku access.
3. The app will monitor and display logs for microphone, camera, and location access.
4. Users receive a persistent notification when monitoring is active.

## 📈 Project Status

### ✅ Features Implemented:
- Real-time log monitoring
- Notification alerts for sensitive access
- Root and Shizuku integration

### ⚠️ Not Included: 
- User-facing controls (no data-blocking or direct app control)
- Support for Android versions below 14

## 📚 Future Work
- **Foreground App Association**: Improve the ability to identify which app triggered an access event.
- **Enhanced Filtering**: Reduce log noise and false positives with smarter log parsing.
- **Cross-Version Support**: Adapt functionality for earlier Android versions.

## 🧑‍💻 Development Highlights

### Challenges:
- Overcoming Android 14 security restrictions.
- Inconsistent log formats across devices.
- Ensuring performance and stability of foreground services.

### Key Learnings:
- Practical use of Shizuku and system-level APIs.
- Handling log noise and real-time parsing challenges.
- Balancing user transparency with technical feasibility.

## 📝 License
This project is licensed under the MIT License.

## 🙏 Acknowledgements
- Shizuku: https://github.com/RikkaApps/Shizuku
- Android open-source community for logcat and debugging tips.
