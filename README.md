# Cymatune

<div align="center">

**Real-Time IMSI Catcher Detection for Android**

[![License](https://img.shields.io/badge/License-Source--Available-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://android.com)

*Detect security loopholes in cell tower communications in real-time that may or may not indicate a fake tower.*

[Features](#-features) • [Detection](#-detection-methods) • [Privacy](#-privacy--security) • [License](#-license)

</div>

---

## 🛡️ Overview

Cymatune is a privacy-focused Android application that identifies potential security anomalies in cellular networks that could indicate IMSI catchers or surveillance equipment, operating in **real-time**. It uses geographic anomaly detection, signal analysis, trust scoring, and logcat monitoring to flag suspicious behavior.

### Key Highlights
- ✅ **Real-Time Alerts** - Instant notifications when threats are detected
- ✅ **42 Detection Methods** - Comprehensive multi-layered analysis
- ✅ **Plain SQLite Storage** - Unencrypted on-device database (no SQLCipher)
- ✅ **Privacy-First** - No analytics, no tracking, no data sharing
- ✅ **Dialer + Contacts** - Full calling functionality retained

---

## ✨ Features

### Detection Methods (42 Total)

**Geographic & Location Analysis**
- Geographic anomaly detection (towers appearing in impossible locations)
- Location jump detection (sudden impossible movement)
- Distance-based trust scoring

**Signal Analysis**
- Signal strength pattern analysis (RSRP, RSRQ, SINR)
- Timing Advance anomaly detection
- CQI/MCS mismatch detection
- Signal fluctuation profiling

**Tower Behavior**
- LAC/CID pattern analysis
- Protocol downgrade detection (5G→4G→3G→2G)
- Encryption strength monitoring (EEA0, 5G-NULL)
- Cipher suite validation (SUPI, AKA, AKA')
- Handshake anomaly detection
- Silent SMS (Class 0) detection

**System Monitoring**
- Real-time logcat threat detection
- Sensor abuse detection (camera/mic without indicator)
- Privacy leak detection
- RIL event analysis
- Network type monitoring (LTE, NSA, SA)

**Trust & Reputation**
- Dynamic trust scoring (0-100)
- Observation count tracking
- Historical behavior profiling
- Multi-SIM support

### User Interface
- **Real-Time Dashboard** - Current tower info, trust score, detection rate
- **Instant Notifications** - Minimal status bar notifications for threats
- **Dialer with tabs** - Dial, Recents, Contacts (fully functional)
- **Tower Details** - Tap any tower to see full technical details
- **Settings** - Notification preferences, about, version info

---

## 🔬 How It Works

Cymatune operates in **real-time** with alerts:

1. **Continuous Monitoring** — The app runs a foreground service that scans cell towers every 30 seconds
2. **Multi-Method Analysis** — Each detection method contributes to a trust score (0-100)
3. **Real-Time Alerting** — When multiple methods flag suspicious behavior, an instant notification is shown
4. **Broadcast Updates** — UI fragments receive live updates via broadcasts
5. **Local Pattern Learning** — Tower observations are stored securely on-device in a standard SQLite database. This allows the app to learn your normal environment over time for improved detection accuracy.

**Data Sovereignty:** Tower observations are kept indefinitely for trust score calculation and local environment learning, giving users full control to manually clear data whenever desired via system settings.

---

## 🔬 Detection Methods (42 Total)

Cymatune utilizes a multi-layered detection engine with 42 distinct methods categorized across five analysis layers.

### 📍 Geographic & Location Analysis
1.  **Geographic Anomaly** — Detects towers reporting coordinates far from their previous known location.
2.  **Sudden LAC Change** — Identifies suspicious Location Area Code transitions while the user is stationary.
3.  **Location Jump Detection** — Flags sudden, physically impossible movements between tower connections.
4.  **Distance-based Trust Scoring** — Scores tower reliability based on its reported proximity to known infrastructure.
5.  **Small Cell Validation** — Cross-references high-signal/low-range towers against small cell CID patterns.
6.  **GPS Jitter Validation** — Distinguishes between legitimate movement and GPS spoofing using accelerometer data.
7.  **Realistic Speed Capping** — Rejects tower handovers occurring at unrealistic terrestrial speeds (>200 km/h).
8.  **Spatial Refinement** — Increases detection precision as more observations are collected from varying angles.

### 📶 Signal Analysis
9.  **Ultra-Low Signal Detection** — Flags towers maintaining connections below standard sensitivity thresholds (<-110 dBm).
10. **Excessive Signal Power** — Detects unusually strong signals (> -45 dBm) that exceed typical macro cell power.
11. **FSPL Physics Validation** — Compares actual signal strength against Free Space Path Loss predictions.
12. **Signal Variance Analysis** — Analyzes signal standard deviation to distinguish moving towers from stationary ones.
13. **Environment-Aware Estimation** — Adjusts distance predictions based on urban, suburban, or rural signal profiles.
14. **Signal Outlier Detection** — Identifies erratic signal patterns that deviate from standard cellular propagation.
15. **Path Loss Anomaly** — Detects mismatches between signal quality (RSRQ/SINR) and raw strength (RSRP).
16. **Carrier Aggregation Consistency** — Validates multi-carrier configurations for anomalies in secondary cells.
17. **Bandwidth Mismatch** — Flags inconsistencies between reported channel bandwidth and actual throughput.
18. **SINR/RSSI Correlation** — Analyzes the relationship between signal-to-noise ratio and received strength.

### 🕒 Tower Behavior & Connectivity
19. **Suspicious Timing Advance** — Monitors TA fluctuations that suggest a tower is adjusting its virtual distance.
20. **Neighbor Masking** — Flags towers reporting zero neighbors despite having an extremely strong signal.
21. **Neighbor Count Instability** — Detects significant, sudden drops in available neighboring cell counts.
22. **Connection Stalling** — Identifies "blackhole" towers that accept connections but do not route data.
23. **Failure Loop Detection** — Flags towers that trap devices in a cycle of repeated registration failures.
24. **Silent Connection Drop** — Detects stealthy disconnects triggered by network-level commands.
25. **"Follower" Detection** — Identifies towers that maintain constant signal strength while the user is moving (Moving Tower Attack).
26. **Data Interception Warning** — Flags strong signals that provide no internet connectivity (potential MitM).

### 🔐 Protocol & Encryption
27. **Protocol Downgrade (5G→2G)** — Detects forced fallback to weaker, less secure legacy protocols.
28. **NULL Cipher Detection** — Flags the use of unencrypted communication (EEA0, 5G-NULL).
29. **Cipher Suite Validation** — Validates the strength of active encryption algorithms (AKA, AKA').
30. **Handshake Parameter Anomaly** — Identifies missing or malformed parameters during network registration.
31. **LAC/CID Pattern Analysis** — Detects non-standard numbering sequences used by rogue equipment.
32. **PCI/EARFCN Consistency** — Cross-references Physical Cell ID with frequency channels for spoofing.
33. **RIL Event Analysis** — Monitors the Radio Interface Layer for low-level system warnings.
34. **Handover Trigger Analysis** — Evaluates the legitimacy of tower-initiated handover requests.

### 📜 System & Logcat Monitoring
35. **Real-Time Logcat Analysis** — Parses system logs for hidden threat indicators (requires READ_LOGS).
36. **Silent SMS (Class 0) Detection** — Detects stealthy "ping" messages used for device tracking.
37. **Identity Request (IMSI) Detection** — Flags unsolicited requests for device identity parameters.
38. **Sensor Abuse Monitoring** — Detects unauthorized attempts to access camera or microphone.
39. **Privacy Leak Detection** — Monitors for system-level resource access during suspicious connections.
40. **Boot-Time Activity Audit** — Inspects background service persistence for malicious behavior.
41. **Service State Machine Validation** — Ensures core detection services have not been tampered with.
42. **Multi-SIM Cross-Validation** — Compares network data across multiple SIM slots for inconsistencies.

---

## 🔒 Privacy & Security

### What the App Accesses
- **Cell tower info** — CID, LAC, MCC, MNC, signal metrics (for detection)
- **Location** — To validate tower positions (stored locally)
- **SMS (optional)** — Reads headers only to detect silent SMS

### What the App Does NOT Do
- ❌ No cloud sync or external servers
- ❌ No analytics, crash reporting, or tracking
- ❌ No data sharing with third parties
- ❌ No external tower databases
- ❌ No historical alert logs (alerts are ephemeral)
- ❌ No export/save functionality

**Data Storage:** All data remains on your device in standard SQLite format (User-Controlled Plaintext Storage). No encryption is used, ensuring full transparency and user sovereignty over their security data.

---

## 📜 License

**Cymatune is source-available under the Cymatune Source-Available License.** See [LICENSE](LICENSE) for terms.

### Open Source Components
- AndroidX (Apache 2.0)
- Material Components (Apache 2.0)
- Kotlin Coroutines (Apache 2.0)
- Room (Apache 2.0)

### Original Implementation
All detection algorithms, architecture, and implementations are original proprietary work with no GPL contamination.

---

## ⚠️ Disclaimer

Cymatune is a **detection tool** for research and personal security.

**Limitations:**
- Cannot guarantee 100% detection of all IMSI catchers
- May produce false positives/negatives
- Cannot block or prevent surveillance
- Effectiveness varies by device, network, and attack sophistication

**Not Tested Against:**
- Real-world IMSI catchers in controlled environments
- All evasion techniques
- Every cellular configuration

**Use Responsibly:** For personal security only. Do not use for illegal surveillance or unauthorized monitoring.

---

## 📞 Support

- **Issues:** [GitHub Issues](https://github.com/sandtuskers/Cymatune/issues)
- **Repository:** https://github.com/sandtuskers/Cymatune

---

<div align="center">

**Made for privacy and security research**

[⬆ Back to top](#cymatune)

</div>
