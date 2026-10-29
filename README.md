# FleetPulse 🚗💨
> **Native Android OBD-II Vehicle Telematics & Driver Behavior Evaluation Platform**

![Android](https://img.shields.io/badge/Platform-Android_11+_(API_30+)-green.svg)
![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)
![Gradle](https://img.shields.io/badge/AGP-9.5.0-blue.svg)
![Mapping](https://img.shields.io/badge/Map-OpenStreetMap_(OSMDroid)-orange.svg)
![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)

---

## 📌 Overview
**FleetPulse** transforms any standard Android smartphone into an advanced automotive telematics unit. By communicating directly with an **ELM327 Bluetooth OBD-II scanner** (RFCOMM SPP) and fusing vehicle ECU telemetry with the phone's high-precision GPS, FleetPulse computes a real-time **Driver Safety Score (0–100)**, displays live powertrain gauges, renders the driven route on an interactive map, and archives every journey in a local SQLite database with instant CSV export.

---

## ✨ Key Features

- **🔌 Plug-and-Play OBD-II**:
  - Direct RFCOMM Bluetooth Classic connection (UUID `00001101-0000-1000-8000-00805F9B34FB`).
  - Automatic memory & one-tap **CONNECT** button (reconnects instantly without re-pairing).
  - Fast-loop PID polling for Speed (`0D`), RPM (`0C`), Engine Load (`04`), and Throttle (`49`).
  - Medium-loop polling for Coolant Temp (`05`), Battery Voltage (`42` / `ATRV`), and Mass Air Flow (`10`).

- **🎯 Driver Behavior Scoring Engine (0–100 Points)**:
  - **Realistic RPM Tiers**: Elevated City Rev ($3,300 - 3,999\text{ RPM}$), High Over-rev ($4,000 - 4,999\text{ RPM}$), and Dangerous Redline ($\ge 5,000\text{ RPM}$).
  - **Cold Engine Strain Protection**: Flags revving above $2,600\text{ RPM}$ while coolant is $< 60^\circ\text{C}$.
  - **1-Second Rolling Derivative for Braking & Acceleration**: Precision delta-v/delta-t detection catches sudden emergency stops ($\ge 8.5\text{ km/h/s}$ and $\ge 13.0\text{ km/h/s}$) without dilution.
  - **Excessive Idling**: Tracks both continuous stops ($> 120\text{s}$) and cumulative city traffic idle ratio ($> 35\%$).
  - **Geo-Tagged Infractions**: When an infraction is detected, a marker pin is dropped on the map at that exact coordinate!

- **🗺️ Live GPS Route Mapping**:
  - Powered by **OpenStreetMap (`osmdroid`)** — zero Google Cloud API keys or billing required!
  - Real-time blue route polyline with live position tracking and start/stop pins.
  - Automatic cache management complying with OSM volunteer server usage policies.

- **⛽ Fuel Economy Tracking**:
  - Live instantaneous consumption ($km/L$) and trip average economy calculated via stoichiometric MAF / Speed-Density formulas.

- **💾 In-App Drive Management & CSV Sharing**:
  - Embedded SQLite database (`fleetpulse.db`) archives trips, route coordinates, and infractions.
  - **"SHARE CSV"** button exports waypoints directly to WhatsApp, Drive, or Email using Android `FileProvider`.
  - **"Delete"** button with confirmation prompts to purge test drives.

---

## 🏎️ Real-World Field Validation

Tested on an **ASUS ROG Phone 5 (Android 14)** connected to an ELM327 Bluetooth adapter during city driving:
- **Duration**: 33 minutes 18 seconds (1,998 seconds)
- **Distance**: 6.51 km
- **Waypoints**: 491 GPS coordinates recorded without loss
- **Peak Speed**: 56 km/h (Average: 12 km/h)
- **Peak Engine RPM**: 2,341 RPM
- **Driver Score**: 100 / 100 (Grade A)
- *Sample route data available in [`samples/trip_route_points.csv`](samples/trip_route_points.csv) and [`samples/trip_route.geojson`](samples/trip_route.geojson).*

---

## 📂 Project Structure

```
OBDProject/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/obd_project/
│   │   │   ├── MainActivity.kt            # Primary dashboard UI & lifecycle coordinator
│   │   │   ├── gps/
│   │   │   │   └── GpsTracker.kt          # Android LocationManager listener & Haversine distance
│   │   │   ├── obd/
│   │   │   │   ├── ObdProtocol.kt         # PID decoders & fuel economy calculator
│   │   │   │   └── Elm327Manager.kt       # RFCOMM socket, AT sequencer & polling loop
│   │   │   ├── scoring/
│   │   │   │   └── DriverScoreEngine.kt   # 100-pt scoring heuristics & sliding window detectors
│   │   │   └── storage/
│   │   │       └── TripDatabaseHelper.kt  # SQLite database (trips, route_points, infractions)
│   │   ├── res/                           # Automotive dark-mode XML layouts & themes
│   │   └── AndroidManifest.xml            # Permissions (Location, Bluetooth Classic, Storage)
│   └── build.gradle.kts                   # Dependencies & AGP 9.5 configuration
├── samples/
│   ├── trip_route_points.csv              # Real-world 491 GPS breadcrumb log
│   └── trip_route.geojson                 # GeoJSON line string for Google Earth / QGIS
├── PROJECT_DOCUMENTATION.md               # Complete engineering specification
└── README.md
```

---

## 🛠️ Build & Installation

### Requirements:
- Android Studio Ladybug / Meerkat (or newer)
- Android SDK 37 (Build-Tools 37.0.0, Platform SDK 37)
- JDK 17 / 21 / 25

### Build via Android Studio:
1. Open the project in Android Studio: `File` $\rightarrow$ `Open` $\rightarrow$ select `OBDProject`.
2. Connect your Android phone with **USB Debugging** enabled.
3. Click the green **Run ▶** button (or `Shift + F10`).

### Build via Command Line:
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📖 In-Depth Engineering Documentation
For complete mathematical formulas, PID response byte tables, ELM327 timing diagrams, and SQLite schemas, refer to:
👉 [**PROJECT_DOCUMENTATION.md**](PROJECT_DOCUMENTATION.md)
