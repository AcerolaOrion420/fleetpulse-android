# FleetPulse: OBD-II Vehicle Telematics & Driver Behavior Platform

## 1. Project Overview & Architecture
**FleetPulse** is a native Android telematics and driver safety evaluation platform. It bridges vehicle onboard diagnostic hardware (ELM327 Bluetooth OBD-II scanners) with Android GPS location providers to capture synchronized powertrain metrics, draw real-time driven route polylines, evaluate driving behavior using physical heuristics (0–100 score), and persist all trip records in a local SQLite database with one-touch CSV export.

```
+-------------------------------------------------------------------------+
|                              Android Device                             |
|                                                                         |
|  +--------------------+   Bluetooth SPP    +-------------------------+  |
|  |   ELM327 Adapter   | <================> |     Elm327Manager       |  |
|  | (Vehicle OBD-II)   |   (RFCOMM Ch 2)    |  - AT Command Sequencer |  |
|  +--------------------+                    |  - PID Fast/Medium Loop |  |
|                                            +------------+------------+  |
|                                                         |               |
|  +--------------------+                                 | Telemetry     |
|  | Android GPS / IMU  | ---- LocationPoint              v               |
|  +---------+----------+         |          +-------------------------+  |
|            |                    |          |   DriverScoreEngine     |  |
|            |                    +--------> |  - 100 pt Base Score    |  |
|            v                               |  - Rolling 1s Derivative|  |
|  +--------------------+                    |  - RPM Tiers & Idle     |  |
|  |     OSMDroid       |                    +------------+------------+  |
|  |  Interactive Map   | <---+                           |               |
|  |  - Blue Polyline   |     | Dropped Infraction Pins   | ScoreReport   |
|  |  - Car Position    | ----+                           v               |
|  +--------------------+                    +-------------------------+  |
|                                            |   TripDatabaseHelper    |  |
|                                            |  (SQLite: trips, route, |  |
|                                            |        infractions)     |  |
|                                            +-------------------------+  |
+-------------------------------------------------------------------------+
```

---

## 2. Hardware & Bluetooth Protocol
- **Adapter Type**: Standard ELM327 Bluetooth Classic OBD-II Adapter (Bluetooth 2.1+ / 4.0 Dual Mode).
- **Bluetooth Profile**: Serial Port Profile (SPP / RFCOMM).
- **Service UUID**: `00001101-0000-1000-8000-00805F9B34FB` (RFCOMM Channel 2).
- **Default PIN**: `1234` or `0000`.

### ELM327 Initialization Sequence
Upon opening the RFCOMM socket, the following sequence is transmitted to configure the chip:
```
1. ATZ       -> Master hardware reset (wait 800ms)
2. ATE0      -> Echo off (reduces transmission overhead)
3. ATL0      -> Linefeeds off (strips \n, returns single-line prompts)
4. ATS0      -> Spaces off (compacts response bytes: "410C1AF8")
5. ATH0      -> Headers off (disables CAN frame IDs)
6. ATSP0     -> Automatic protocol detection (ISO 15765-4 CAN, K-Line, etc.)
7. ATAT2     -> Adaptive timing level 2 (fast baud rate response)
```
Each command is terminated by `\r` (`0x0D`), and the receiver awaits the ELM327 prompt character `>` (`0x3E`).

---

## 3. OBD-II Parameter IDs (PIDs) & Decoders

| Mode / PID | Name | Polling Loop | Formula / Decode | Unit |
| :--- | :--- | :--- | :--- | :--- |
| **`01 0D`** | Vehicle Speed | Fast (~3 Hz) | $A$ | $\text{km/h}$ |
| **`01 0C`** | Engine RPM | Fast (~3 Hz) | $\frac{(A \times 256) + B}{4}$ | $\text{RPM}$ |
| **`01 04`** | Engine Load | Fast (~3 Hz) | $\text{round}\left(\frac{A \times 100}{255}\right)$ | $\%$ |
| **`01 49`** | Accelerator Pedal | Fast (~3 Hz) | $\text{round}\left(\frac{A \times 100}{255}\right)$ | $\%$ |
| **`01 05`** | Coolant Temp | Medium (~0.3 Hz) | $A - 40$ | $^\circ\text{C}$ |
| **`01 10`** | Mass Air Flow (MAF)| Medium (~0.3 Hz) | $\frac{(A \times 256) + B}{100.0}$ | $\text{g/s}$ |
| **`01 42`** | Battery Voltage | Medium (~0.3 Hz) | $\frac{(A \times 256) + B}{1000.0}$ or `ATRV` | $\text{V}$ |

### Fuel Economy Calculation ($km/L$)
Fuel consumption is calculated using the stoichiometric air-fuel ratio ($14.7 : 1$ for gasoline, fuel density $\approx 820\text{ g/L}$):
$$\text{Fuel Flow (L/h)} = \frac{\text{MAF (g/s)} \times 3600}{14.7 \times 820} \approx \frac{\text{MAF} \times 3600}{12054}$$
$$\text{Instantaneous Fuel Economy (km/L)} = \frac{\text{Speed (km/h)}}{\text{Fuel Flow (L/h)}}$$
*Fallback for vehicles without direct MAF*: Speed-Density estimation based on displacement ($1.2\text{L}$ default), RPM, and Engine Load $\%$.

---

## 4. Driver Behavior Scoring Heuristics
The driver starts with **100 base points**. Real-time deductions occur based on empirical powertrain metrics:

### A. Realistic RPM Tiers
- **Tier 1 (Elevated City Rev)**: $3,300\text{ to }3,999\text{ RPM}$ $\rightarrow$ $-2$ points (Cooldown: 4s).
- **Tier 2 (High Over-Rev)**: $4,000\text{ to }4,999\text{ RPM}$ $\rightarrow$ $-4$ points (Cooldown: 4s).
- **Tier 3 (Dangerous Redline)**: $\ge 5,000\text{ RPM}$ $\rightarrow$ $-6$ points (Cooldown: 4s).
- **Cold Engine Strain**: Revving $> 2,600\text{ RPM}$ while Coolant Temp $< 60^\circ\text{C}$ $\rightarrow$ $-3$ points (Cooldown: 6s).

### B. 1-Second Rolling Derivative for Braking & Acceleration
Instead of averaging across wide windows, the engine looks at the sample nearest to $1.0\text{s}$ ago ($\Delta t \in [0.7\text{s}, 1.6\text{s}]$):
$$\text{Deceleration Rate} = -\frac{V_{\text{current}} - V_{t-1\text{s}}}{\Delta t}\quad (\text{km/h/s})$$
- **Moderate Harsh Braking**: $\ge 8.5\text{ km/h/s}$ ($\sim 0.24g$) $\rightarrow$ $-3$ points (Cooldown: 3s).
- **Severe Emergency Braking**: $\ge 13.0\text{ km/h/s}$ ($\sim 0.37g$) $\rightarrow$ $-6$ points (Cooldown: 3s).
- **Harsh Acceleration**: Acceleration $\ge 9.5\text{ km/h/s}$ while Engine Load $> 65\%$ $\rightarrow$ $-3$ points (Cooldown: 3s).

### C. Cumulative & Contiguous Idling
- **Continuous Stationary Stop**: Speed $= 0\text{ km/h}$ and $\text{RPM} > 350$ for $> 120\text{s}$ $\rightarrow$ $-2$ points per additional minute.
- **Excessive Cumulative Idle**: Idle duration $> 35\%$ of total trip time after at least 5 minutes $\rightarrow$ $-2$ points per 2 minutes.

### D. Over-Speeding
- Speed $> 120\text{ km/h}$ $\rightarrow$ $-5$ points (Cooldown: 15s).

### E. Letter Grades
- **Grade A**: $90 - 100$ (Optimal & Safe)
- **Grade B**: $80 - 89$ (Normal / Minor deviations)
- **Grade C**: $70 - 79$ (Moderate / Frequent revs or sudden stops)
- **Grade D**: $60 - 69$ (Aggressive)
- **Grade F**: $< 60$ (Hazardous)

---

## 5. Local SQLite Database Schema
Database file: `fleetpulse.db` (Database Version: 2).

### Table: `trips`
| Column | Type | Description |
| :--- | :--- | :--- |
| `id` | TEXT PRIMARY KEY | Unique trip UUID |
| `start_time` | INTEGER | Epoch timestamp (ms) |
| `end_time` | INTEGER | Epoch timestamp (ms) |
| `duration_seconds`| INTEGER | Total elapsed time |
| `distance_km` | REAL | Total GPS distance via Haversine |
| `max_speed` | INTEGER | Peak speed recorded (km/h) |
| `avg_speed` | INTEGER | Mean moving speed (km/h) |
| `max_rpm` | INTEGER | Peak engine RPM recorded |
| `idle_seconds` | INTEGER | Total stationary idle time |
| `score` | INTEGER | Final driver score (0–100) |
| `grade` | TEXT | Letter grade ("A" to "F") |
| `infraction_count`| INTEGER | Total infractions recorded |
| `avg_fuel_economy_kml` | REAL | Average fuel economy (km/L) |

### Table: `route_points`
| Column | Type | Description |
| :--- | :--- | :--- |
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT | Point index |
| `trip_id` | TEXT | Foreign key referencing `trips.id` |
| `latitude` | REAL | WGS84 Latitude |
| `longitude`| REAL | WGS84 Longitude |
| `speed_kmh`| REAL | GPS speed (km/h) |
| `timestamp`| INTEGER | Fix epoch timestamp (ms) |

### Table: `infractions`
| Column | Type | Description |
| :--- | :--- | :--- |
| `id` | TEXT PRIMARY KEY | Infraction UUID |
| `trip_id` | TEXT | Foreign key referencing `trips.id` |
| `type` | TEXT | `OVER_REV`, `HARSH_BRAKE`, `HARSH_ACCEL`, etc. |
| `title` | TEXT | Descriptive label |
| `penalty` | INTEGER | Deduction value |
| `details` | TEXT | Quantitative reason (e.g. "Decel at 14.2 km/h/s") |
| `speed` | INTEGER | Speed at event moment |
| `rpm` | INTEGER | RPM at event moment |
| `latitude` | REAL | Latitude where event occurred |
| `longitude`| REAL | Longitude where event occurred |
| `timestamp`| INTEGER | Event epoch timestamp (ms) |

---

## 6. Real-World Field Validation (Test Drive #1)
- **Date**: October 29, 2026
- **Device**: ASUS ROG Phone 5 (Android 14, `ASUS_I005DA`)
- **Duration**: 33 minutes 18 seconds (1,998 seconds)
- **Distance**: 6.51 km
- **Waypoints**: 491 GPS breadcrumbs recorded without drift
- **Peak Speed**: 56 km/h (Avg: 12 km/h)
- **Peak Engine RPM**: 2,341 RPM
- **Stationary Idling**: 252 seconds
- **Infractions**: 0 (Clean, smooth driving)
- **Score**: 100 / 100 (Grade A)
- *Sample route points are saved in `samples/trip_route_points.csv` and `samples/trip_route.geojson`.*

---

## 7. Development & Build Setup
- **Android Studio Version**: Android Studio Ladybug / Meerkat (AGP 9.5.0)
- **Gradle Version**: 9.6.0
- **Java / JDK**: Java 17 / 21 / 25 (JBR bundled in `/opt/android-studio/jbr`)
- **Target SDK**: Android 37 (Minimum SDK: 30)

### Quick CLI Build
```bash
./gradlew assembleDebug
```
The APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Quick Run via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.fleetpulse.app/com.example.obd_project.MainActivity
```
