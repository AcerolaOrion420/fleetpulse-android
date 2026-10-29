package com.example.obd_project.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.obd_project.gps.LocationPoint
import com.example.obd_project.scoring.DriverInfraction
import com.example.obd_project.scoring.InfractionType
import com.example.obd_project.scoring.ScoreReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class SavedTrip(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val durationSeconds: Long,
    val distanceKm: Double,
    val maxSpeed: Int,
    val avgSpeed: Int,
    val maxRpm: Int,
    val idleSeconds: Long,
    val score: Int,
    val grade: String,
    val infractionCount: Int,
    val avgFuelEconomyKmL: Double = 0.0
)

class TripDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "fleetpulse.db"
        const val DATABASE_VERSION = 2

        const val TABLE_TRIPS = "trips"
        const val TABLE_ROUTE = "route_points"
        const val TABLE_INFRACTIONS = "infractions"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_TRIPS (
                id TEXT PRIMARY KEY,
                start_time INTEGER,
                end_time INTEGER,
                duration_seconds INTEGER,
                distance_km REAL,
                max_speed INTEGER,
                avg_speed INTEGER,
                max_rpm INTEGER,
                idle_seconds INTEGER,
                score INTEGER,
                grade TEXT,
                infraction_count INTEGER,
                avg_fuel_economy_kml REAL DEFAULT 0.0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_ROUTE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trip_id TEXT,
                latitude REAL,
                longitude REAL,
                speed_kmh REAL,
                timestamp INTEGER,
                FOREIGN KEY(trip_id) REFERENCES $TABLE_TRIPS(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_INFRACTIONS (
                id TEXT PRIMARY KEY,
                trip_id TEXT,
                type TEXT,
                title TEXT,
                penalty INTEGER,
                details TEXT,
                speed INTEGER,
                rpm INTEGER,
                latitude REAL,
                longitude REAL,
                timestamp INTEGER,
                FOREIGN KEY(trip_id) REFERENCES $TABLE_TRIPS(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRIPS ADD COLUMN avg_fuel_economy_kml REAL DEFAULT 0.0")
            } catch (_: Exception) {}
        }
    }

    fun saveTrip(tripId: String, startTime: Long, report: ScoreReport, points: List<LocationPoint>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val tripValues = ContentValues().apply {
                put("id", tripId)
                put("start_time", startTime)
                put("end_time", System.currentTimeMillis())
                put("duration_seconds", report.stats.durationSeconds)
                put("distance_km", report.stats.distanceKm)
                put("max_speed", report.stats.maxSpeedKmh)
                put("avg_speed", report.stats.avgSpeedKmh)
                put("max_rpm", report.stats.maxRpm)
                put("idle_seconds", report.stats.idleSeconds)
                put("score", report.score)
                put("grade", report.grade)
                put("infraction_count", report.infractions.size)
                put("avg_fuel_economy_kml", report.stats.avgFuelEconomyKmL)
            }
            db.insertWithOnConflict(TABLE_TRIPS, null, tripValues, SQLiteDatabase.CONFLICT_REPLACE)

            for (pt in points) {
                val ptValues = ContentValues().apply {
                    put("trip_id", tripId)
                    put("latitude", pt.latitude)
                    put("longitude", pt.longitude)
                    put("speed_kmh", pt.speedKmh)
                    put("timestamp", pt.timestamp)
                }
                db.insert(TABLE_ROUTE, null, ptValues)
            }

            for (inf in report.infractions) {
                val infValues = ContentValues().apply {
                    put("id", inf.id)
                    put("trip_id", tripId)
                    put("type", inf.type.name)
                    put("title", inf.title)
                    put("penalty", inf.penalty)
                    put("details", inf.details)
                    put("speed", inf.speed)
                    put("rpm", inf.rpm)
                    put("latitude", inf.latitude)
                    put("longitude", inf.longitude)
                    put("timestamp", inf.timestamp)
                }
                db.insert(TABLE_INFRACTIONS, null, infValues)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAllTrips(): List<SavedTrip> {
        val trips = mutableListOf<SavedTrip>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_TRIPS ORDER BY start_time DESC", null)
        cursor.use {
            val kmlColIndex = it.getColumnIndex("avg_fuel_economy_kml")
            while (it.moveToNext()) {
                val fuelKmL = if (kmlColIndex != -1 && !it.isNull(kmlColIndex)) it.getDouble(kmlColIndex) else 0.0
                trips.add(
                    SavedTrip(
                        id = it.getString(it.getColumnIndexOrThrow("id")),
                        startTime = it.getLong(it.getColumnIndexOrThrow("start_time")),
                        endTime = it.getLong(it.getColumnIndexOrThrow("end_time")),
                        durationSeconds = it.getLong(it.getColumnIndexOrThrow("duration_seconds")),
                        distanceKm = it.getDouble(it.getColumnIndexOrThrow("distance_km")),
                        maxSpeed = it.getInt(it.getColumnIndexOrThrow("max_speed")),
                        avgSpeed = it.getInt(it.getColumnIndexOrThrow("avg_speed")),
                        maxRpm = it.getInt(it.getColumnIndexOrThrow("max_rpm")),
                        idleSeconds = it.getLong(it.getColumnIndexOrThrow("idle_seconds")),
                        score = it.getInt(it.getColumnIndexOrThrow("score")),
                        grade = it.getString(it.getColumnIndexOrThrow("grade")),
                        infractionCount = it.getInt(it.getColumnIndexOrThrow("infraction_count")),
                        avgFuelEconomyKmL = fuelKmL
                    )
                )
            }
        }
        return trips
    }

    fun deleteTrip(tripId: String): Boolean {
        val db = writableDatabase
        db.delete(TABLE_ROUTE, "trip_id = ?", arrayOf(tripId))
        db.delete(TABLE_INFRACTIONS, "trip_id = ?", arrayOf(tripId))
        val count = db.delete(TABLE_TRIPS, "id = ?", arrayOf(tripId))
        return count > 0
    }

    fun getTripRoute(tripId: String): List<LocationPoint> {
        val points = mutableListOf<LocationPoint>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_ROUTE WHERE trip_id = ? ORDER BY id ASC", arrayOf(tripId))
        cursor.use {
            while (it.moveToNext()) {
                points.add(
                    LocationPoint(
                        latitude = it.getDouble(it.getColumnIndexOrThrow("latitude")),
                        longitude = it.getDouble(it.getColumnIndexOrThrow("longitude")),
                        speedKmh = it.getFloat(it.getColumnIndexOrThrow("speed_kmh")),
                        timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp"))
                    )
                )
            }
        }
        return points
    }

    fun getTripInfractions(tripId: String): List<DriverInfraction> {
        val infractions = mutableListOf<DriverInfraction>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_INFRACTIONS WHERE trip_id = ? ORDER BY timestamp ASC", arrayOf(tripId))
        cursor.use {
            while (it.moveToNext()) {
                infractions.add(
                    DriverInfraction(
                        id = it.getString(it.getColumnIndexOrThrow("id")),
                        timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                        type = InfractionType.valueOf(it.getString(it.getColumnIndexOrThrow("type"))),
                        title = it.getString(it.getColumnIndexOrThrow("title")),
                        penalty = it.getInt(it.getColumnIndexOrThrow("penalty")),
                        details = it.getString(it.getColumnIndexOrThrow("details")),
                        speed = it.getInt(it.getColumnIndexOrThrow("speed")),
                        rpm = it.getInt(it.getColumnIndexOrThrow("rpm")),
                        latitude = if (it.isNull(it.getColumnIndexOrThrow("latitude"))) null else it.getDouble(it.getColumnIndexOrThrow("latitude")),
                        longitude = if (it.isNull(it.getColumnIndexOrThrow("longitude"))) null else it.getDouble(it.getColumnIndexOrThrow("longitude"))
                    )
                )
            }
        }
        return infractions
    }

    fun generateTripCsv(tripId: String): String {
        val points = getTripRoute(tripId)
        val sb = StringBuilder()
        sb.append("id,trip_id,latitude,longitude,speed_kmh,timestamp,iso_time\n")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        for ((idx, pt) in points.withIndex()) {
            val iso = dateFormat.format(Date(pt.timestamp))
            sb.append("${idx + 1},$tripId,${pt.latitude},${pt.longitude},${pt.speedKmh},${pt.timestamp},$iso\n")
        }
        return sb.toString()
    }
}
