package com.example.obd_project.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log

data class LocationPoint(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float,
    val timestamp: Long = System.currentTimeMillis()
)

class GpsTracker(private val context: Context) {

    companion object {
        private const val TAG = "GpsTracker"
        private const val MIN_TIME_MS = 1000L
        private const val MIN_DISTANCE_M = 1.0f
    }

    interface Listener {
        fun onLocationUpdate(point: LocationPoint, totalDistanceKm: Double)
    }

    private var listener: Listener? = null
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var isTracking = false
    private var lastLocation: Location? = null
    private var totalDistanceMeters = 0.0
    private val breadcrumbs = mutableListOf<LocationPoint>()

    fun setListener(l: Listener?) {
        this.listener = l
    }

    val totalDistanceKm: Double
        get() = totalDistanceMeters / 1000.0

    val points: List<LocationPoint>
        @Synchronized get() = breadcrumbs.toList()

    val currentLocation: LocationPoint?
        @Synchronized get() = breadcrumbs.lastOrNull()

    private val internalListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Ignore inaccurate readings
            if (location.hasAccuracy() && location.accuracy > 35f) {
                return
            }

            synchronized(this@GpsTracker) {
                if (lastLocation != null) {
                    val dist = lastLocation!!.distanceTo(location)
                    // Avoid GPS drift while stationary (< 1.5m)
                    if (dist > 1.5f) {
                        totalDistanceMeters += dist
                    }
                }
                lastLocation = location

                val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
                val point = LocationPoint(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    speedKmh = speedKmh,
                    timestamp = location.time
                )
                breadcrumbs.add(point)

                listener?.onLocationUpdate(point, totalDistanceKm)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isTracking) return
        breadcrumbs.clear()
        totalDistanceMeters = 0.0
        lastLocation = null

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    internalListener
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    internalListener
                )
            }
            isTracking = true
            Log.d(TAG, "GPS Tracking started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GPS: ${e.message}", e)
        }
    }

    fun stop() {
        if (!isTracking) return
        try {
            locationManager.removeUpdates(internalListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove location updates: ${e.message}")
        }
        isTracking = false
        Log.d(TAG, "GPS Tracking stopped")
    }
}
