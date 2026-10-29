package com.example.obd_project.scoring

import java.util.Locale
import java.util.UUID

enum class InfractionType {
    OVER_REV,
    HARSH_BRAKE,
    HARSH_ACCEL,
    EXCESSIVE_IDLE,
    OVER_SPEED
}

data class DriverInfraction(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: InfractionType,
    val title: String,
    val penalty: Int,
    val details: String,
    val speed: Int,
    val rpm: Int,
    val latitude: Double? = null,
    val longitude: Double? = null
)

data class TripStats(
    val durationSeconds: Long = 0,
    val maxSpeedKmh: Int = 0,
    val avgSpeedKmh: Int = 0,
    val maxRpm: Int = 0,
    val idleSeconds: Long = 0,
    val totalInfractions: Int = 0,
    val distanceKm: Double = 0.0,
    val avgFuelEconomyKmL: Double = 0.0
)

data class ScoreReport(
    val score: Int,
    val grade: String,
    val infractions: List<DriverInfraction>,
    val stats: TripStats
)

class DriverScoreEngine {
    private var baseScore: Int = 100
    private val infractions = mutableListOf<DriverInfraction>()

    // Sliding window buffers (last 5 seconds)
    private data class TimestampedVal(val t: Long, val v: Double)
    private val speedWindow = mutableListOf<TimestampedVal>()
    private val rpmWindow = mutableListOf<TimestampedVal>()
    private val loadWindow = mutableListOf<TimestampedVal>()

    // Idle tracking
    private var idleStartTime: Long? = null
    private var totalIdleSeconds: Long = 0
    private var penalizedIdleMinutes: Int = 0

    // Infraction cooldowns
    private var lastOverRevTime: Long = 0
    private var lastHarshBrakeTime: Long = 0
    private var lastHarshAccelTime: Long = 0
    private var lastOverSpeedTime: Long = 0

    // Aggregated metrics
    private var maxSpeed: Int = 0
    private var maxRpm: Int = 0
    private var speedAccumulator: Long = 0
    private var sampleCount: Long = 0
    private val tripStartTime: Long = System.currentTimeMillis()

    // Fuel economy tracking
    private var fuelEconomySum: Double = 0.0
    private var fuelEconomySamples: Long = 0

    @Synchronized
    fun processCycle(
        speed: Int,
        rpm: Int,
        engineLoadPct: Int,
        coolantTempC: Int = 0,
        fuelEconomyKmL: Double = 0.0,
        latitude: Double? = null,
        longitude: Double? = null
    ): Int {
        val now = System.currentTimeMillis()

        maxSpeed = maxOf(maxSpeed, speed)
        maxRpm = maxOf(maxRpm, rpm)
        speedAccumulator += speed
        sampleCount++

        if (speed > 5 && fuelEconomyKmL > 0.5) {
            fuelEconomySum += fuelEconomyKmL
            fuelEconomySamples++
        }

        speedWindow.add(TimestampedVal(now, speed.toDouble()))
        rpmWindow.add(TimestampedVal(now, rpm.toDouble()))
        loadWindow.add(TimestampedVal(now, engineLoadPct.toDouble()))
        pruneWindows(now, 5000L)

        // 1. Realistic RPM Tiers & Cold Engine Abuse Check
        if (now - lastOverRevTime > 4000L) {
            when {
                // Tier 3: Dangerous Redline (>= 5000 RPM)
                rpm >= 5000 -> {
                    recordInfraction(
                        DriverInfraction(
                            type = InfractionType.OVER_REV,
                            title = "Dangerous Redline",
                            penalty = 6,
                            details = "Engine pushed to $rpm RPM",
                            speed = speed,
                            rpm = rpm,
                            latitude = latitude,
                            longitude = longitude
                        )
                    )
                    lastOverRevTime = now
                }
                // Tier 2: Severe Over-rev (4000..4999 RPM)
                rpm in 4000..4999 -> {
                    recordInfraction(
                        DriverInfraction(
                            type = InfractionType.OVER_REV,
                            title = "High Over-Rev",
                            penalty = 4,
                            details = "Engine revving high at $rpm RPM",
                            speed = speed,
                            rpm = rpm,
                            latitude = latitude,
                            longitude = longitude
                        )
                    )
                    lastOverRevTime = now
                }
                // Tier 1: Elevated RPM in city driving (3300..3999 RPM)
                rpm in 3300..3999 -> {
                    recordInfraction(
                        DriverInfraction(
                            type = InfractionType.OVER_REV,
                            title = "Elevated Engine Rev",
                            penalty = 2,
                            details = "Aggressive shift/gear at $rpm RPM",
                            speed = speed,
                            rpm = rpm,
                            latitude = latitude,
                            longitude = longitude
                        )
                    )
                    lastOverRevTime = now
                }
                // Cold Engine High Rev (< 60°C coolant and > 2600 RPM)
                coolantTempC in 1..59 && rpm > 2600 -> {
                    recordInfraction(
                        DriverInfraction(
                            type = InfractionType.OVER_REV,
                            title = "Cold Engine Strain",
                            penalty = 3,
                            details = "Revved to $rpm RPM with cold coolant (${coolantTempC}°C)",
                            speed = speed,
                            rpm = rpm,
                            latitude = latitude,
                            longitude = longitude
                        )
                    )
                    lastOverRevTime = now
                }
            }
        }

        // 2. Focused 1-Second Rolling Derivative for Braking & Acceleration
        val oneSecAgoSample = speedWindow.minByOrNull { Math.abs((now - it.t) - 1000L) }
        if (oneSecAgoSample != null) {
            val dtSec = (now - oneSecAgoSample.t) / 1000.0
            if (dtSec in 0.7..1.6) {
                val dV = speed - oneSecAgoSample.v
                val decelRate = -dV / dtSec // km/h per second

                // Harsh Braking: decelRate >= 8.5 km/h/s (~0.24g)
                if (decelRate >= 8.5 && (now - lastHarshBrakeTime > 3000L)) {
                    val isSevere = decelRate >= 13.0
                    val penalty = if (isSevere) 6 else 3
                    val title = if (isSevere) "Emergency Hard Brake" else "Harsh Braking"
                    recordInfraction(
                        DriverInfraction(
                            type = InfractionType.HARSH_BRAKE,
                            title = title,
                            penalty = penalty,
                            details = "Decelerated at ${String.format(Locale.US, "%.1f", decelRate)} km/h/s",
                            speed = speed,
                            rpm = rpm,
                            latitude = latitude,
                            longitude = longitude
                        )
                    )
                    lastHarshBrakeTime = now
                }

                // Harsh Acceleration: accelRate >= 9.5 km/h/s with engine load > 65%
                val accelRate = dV / dtSec
                if (accelRate >= 9.5 && engineLoadPct > 65 && (now - lastHarshAccelTime > 3000L)) {
                    recordInfraction(
                        DriverInfraction(
                            type = InfractionType.HARSH_ACCEL,
                            title = "Harsh Acceleration",
                            penalty = 3,
                            details = "Rapid burst at ${String.format(Locale.US, "%.1f", accelRate)} km/h/s (Load ${engineLoadPct}%)",
                            speed = speed,
                            rpm = rpm,
                            latitude = latitude,
                            longitude = longitude
                        )
                    )
                    lastHarshAccelTime = now
                }
            }
        }

        // 3. Cumulative & Contiguous Idling Tracking
        if (speed == 0 && rpm > 350) {
            totalIdleSeconds++
            val tripDurationSec = maxOf(1L, (now - tripStartTime) / 1000L)
            val idleRatio = totalIdleSeconds.toDouble() / tripDurationSec.toDouble()

            val contiguousIdleSec = if (idleStartTime != null) (now - idleStartTime!!) / 1000L else 0L
            if (idleStartTime == null) idleStartTime = now

            if (contiguousIdleSec >= 120L && (contiguousIdleSec / 60).toInt() > penalizedIdleMinutes) {
                recordInfraction(
                    DriverInfraction(
                        type = InfractionType.EXCESSIVE_IDLE,
                        title = "Excessive Idling",
                        penalty = 2,
                        details = "Continuous stationary idle for ${contiguousIdleSec / 60}m",
                        speed = speed,
                        rpm = rpm,
                        latitude = latitude,
                        longitude = longitude
                    )
                )
                penalizedIdleMinutes = (contiguousIdleSec / 60).toInt()
            } else if (tripDurationSec >= 300 && idleRatio > 0.35 && (totalIdleSeconds / 120).toInt() > penalizedIdleMinutes) {
                recordInfraction(
                    DriverInfraction(
                        type = InfractionType.EXCESSIVE_IDLE,
                        title = "High Cumulative Idle",
                        penalty = 2,
                        details = "Idled ${totalIdleSeconds / 60}m (${(idleRatio * 100).toInt()}% of trip)",
                        speed = speed,
                        rpm = rpm,
                        latitude = latitude,
                        longitude = longitude
                    )
                )
                penalizedIdleMinutes = (totalIdleSeconds / 120).toInt()
            }
        } else {
            idleStartTime = null
        }

        // 4. Over-speeding (> 120 km/h)
        if (speed > 120 && (now - lastOverSpeedTime > 15000L)) {
            recordInfraction(
                DriverInfraction(
                    type = InfractionType.OVER_SPEED,
                    title = "Over-Speeding Warning",
                    penalty = 5,
                    details = "Vehicle speed recorded at $speed km/h",
                    speed = speed,
                    rpm = rpm,
                    latitude = latitude,
                    longitude = longitude
                )
            )
            lastOverSpeedTime = now
        }

        return currentScore
    }

    private fun pruneWindows(now: Long, maxAgeMs: Long) {
        val cutoff = now - maxAgeMs
        speedWindow.removeAll { it.t < cutoff }
        rpmWindow.removeAll { it.t < cutoff }
        loadWindow.removeAll { it.t < cutoff }
    }

    private fun recordInfraction(infraction: DriverInfraction) {
        infractions.add(infraction)
        baseScore = maxOf(0, baseScore - infraction.penalty)
    }

    val currentScore: Int
        get() = maxOf(0, minOf(100, baseScore))

    val currentGrade: String
        get() = when {
            baseScore >= 90 -> "A"
            baseScore >= 80 -> "B"
            baseScore >= 70 -> "C"
            baseScore >= 60 -> "D"
            else -> "F"
        }

    val allInfractions: List<DriverInfraction>
        get() = infractions.toList()

    fun generateReport(distanceKm: Double = 0.0): ScoreReport {
        val durationSec = (System.currentTimeMillis() - tripStartTime) / 1000L
        val avgSpeed = if (sampleCount > 0) (speedAccumulator / sampleCount).toInt() else 0
        val avgFuelEconomy = if (fuelEconomySamples > 0) Math.round((fuelEconomySum / fuelEconomySamples) * 10.0) / 10.0 else 0.0

        return ScoreReport(
            score = currentScore,
            grade = currentGrade,
            infractions = allInfractions,
            stats = TripStats(
                durationSeconds = durationSec,
                maxSpeedKmh = maxSpeed,
                avgSpeedKmh = avgSpeed,
                maxRpm = maxRpm,
                idleSeconds = totalIdleSeconds,
                totalInfractions = infractions.size,
                distanceKm = distanceKm,
                avgFuelEconomyKmL = avgFuelEconomy
            )
        )
    }
}
