package com.example.obd_project

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.obd_project.gps.GpsTracker
import com.example.obd_project.gps.LocationPoint
import com.example.obd_project.obd.Elm327Manager
import com.example.obd_project.obd.TelemetrySample
import com.example.obd_project.scoring.DriverInfraction
import com.example.obd_project.scoring.DriverScoreEngine
import com.example.obd_project.scoring.ScoreReport
import com.example.obd_project.storage.SavedTrip
import com.example.obd_project.storage.TripDatabaseHelper
import com.google.android.material.button.MaterialButton
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "fleetpulse_prefs"
        private const val KEY_LAST_DEVICE_ADDR = "last_device_addr"
        private const val KEY_LAST_DEVICE_NAME = "last_device_name"
    }

    // Views
    private lateinit var tvBtStatus: TextView
    private lateinit var btnQuickConnect: MaterialButton
    private lateinit var btnTripHistory: MaterialButton
    private lateinit var tvTripTimer: TextView
    private lateinit var tvTripDistance: TextView
    private lateinit var btnStartEndTrip: MaterialButton
    private lateinit var scoreRingContainer: RelativeLayout
    private lateinit var tvScoreNumber: TextView
    private lateinit var tvScoreGrade: TextView
    private lateinit var tvScoreStatus: TextView
    private lateinit var tvInfractionsCount: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvMaxSpeed: TextView
    private lateinit var tvRpm: TextView
    private lateinit var tvMaxRpm: TextView
    private lateinit var tvFuelEconomy: TextView
    private lateinit var tvAvgFuelEconomy: TextView
    private lateinit var tvEngineLoad: TextView
    private lateinit var tvCoolantTemp: TextView
    private lateinit var tvThrottle: TextView
    private lateinit var tvVoltage: TextView
    private lateinit var btnCenterMap: MaterialButton
    private lateinit var mapView: MapView

    // Engines & Managers
    private val elmManager = Elm327Manager()
    private lateinit var gpsTracker: GpsTracker
    private lateinit var dbHelper: TripDatabaseHelper
    private var scoreEngine: DriverScoreEngine? = null
    private lateinit var appPrefs: SharedPreferences

    // State
    private var isTripActive = false
    private var currentTripId: String? = null
    private var tripStartTimeMs: Long = 0
    private var maxRecordedSpeed = 0
    private var maxRecordedRpm = 0

    // Map Overlays
    private lateinit var routePolyline: Polyline
    private var carMarker: Marker? = null

    // Timers
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isTripActive) {
                val elapsedSec = (System.currentTimeMillis() - tripStartTimeMs) / 1000L
                val hh = elapsedSec / 3600
                val mm = (elapsedSec % 3600) / 60
                val ss = elapsedSec % 60
                tvTripTimer.text = String.format(Locale.US, "%02d:%02d:%02d", hh, mm, ss)
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Permissions required for GPS & OBD Bluetooth", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Initialize OSMDroid with compliant User-Agent per OpenStreetMap Tile Usage Policy
        val prefs = getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
        Configuration.getInstance().load(this, prefs)
        Configuration.getInstance().userAgentValue = "FleetPulse-Vehicle-Telematics/1.0 (Linux; Android 14; contact: fleetpulse-dev@fleetpulse.org)"

        // Purge any previously cached blocked/error tiles
        try {
            val cacheFolder = File(cacheDir, "osmdroid")
            if (cacheFolder.exists()) cacheFolder.deleteRecursively()
            getExternalFilesDir(null)?.let { ext ->
                val extCache = File(ext, "osmdroid")
                if (extCache.exists()) extCache.deleteRecursively()
            }
        } catch (_: Exception) {}

        setContentView(R.layout.activity_main)

        initViews()
        initMap()
        checkPermissions()

        gpsTracker = GpsTracker(this)
        dbHelper = TripDatabaseHelper(this)

        setupListeners()
        updateSavedDeviceDisplay()
    }

    private fun initViews() {
        tvBtStatus = findViewById(R.id.tvBtStatus)
        btnQuickConnect = findViewById(R.id.btnQuickConnect)
        btnTripHistory = findViewById(R.id.btnTripHistory)
        tvTripTimer = findViewById(R.id.tvTripTimer)
        tvTripDistance = findViewById(R.id.tvTripDistance)
        btnStartEndTrip = findViewById(R.id.btnStartEndTrip)
        scoreRingContainer = findViewById(R.id.scoreRingContainer)
        tvScoreNumber = findViewById(R.id.tvScoreNumber)
        tvScoreGrade = findViewById(R.id.tvScoreGrade)
        tvScoreStatus = findViewById(R.id.tvScoreStatus)
        tvInfractionsCount = findViewById(R.id.tvInfractionsCount)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvMaxSpeed = findViewById(R.id.tvMaxSpeed)
        tvRpm = findViewById(R.id.tvRpm)
        tvMaxRpm = findViewById(R.id.tvMaxRpm)
        tvFuelEconomy = findViewById(R.id.tvFuelEconomy)
        tvAvgFuelEconomy = findViewById(R.id.tvAvgFuelEconomy)
        tvEngineLoad = findViewById(R.id.tvEngineLoad)
        tvCoolantTemp = findViewById(R.id.tvCoolantTemp)
        tvThrottle = findViewById(R.id.tvThrottle)
        tvVoltage = findViewById(R.id.tvVoltage)
        btnCenterMap = findViewById(R.id.btnCenterMap)
        mapView = findViewById(R.id.mapView)
    }

    private fun initMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.tileProvider.clearTileCache()
        mapView.setMultiTouchControls(true)
        val controller = mapView.controller
        controller.setZoom(16.5)

        val defaultPoint = GeoPoint(12.9716, 77.5946)
        controller.setCenter(defaultPoint)

        routePolyline = Polyline().apply {
            outlinePaint.color = Color.parseColor("#3B82F6")
            outlinePaint.strokeWidth = 10f
        }
        mapView.overlayManager.add(routePolyline)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun updateSavedDeviceDisplay() {
        val savedName = appPrefs.getString(KEY_LAST_DEVICE_NAME, null)
        if (savedName != null) {
            tvBtStatus.text = "⚪ $savedName"
            btnQuickConnect.text = "CONNECT"
            btnQuickConnect.visibility = View.VISIBLE
        } else {
            tvBtStatus.text = "⚪ Select OBD-II"
            btnQuickConnect.text = "PAIR"
            btnQuickConnect.visibility = View.VISIBLE
        }
    }

    private fun setupListeners() {
        tvBtStatus.setOnClickListener {
            showBluetoothDevicePicker()
        }

        btnQuickConnect.setOnClickListener {
            if (elmManager.isConnected) {
                elmManager.disconnect()
            } else {
                quickConnectSavedDevice()
            }
        }

        btnStartEndTrip.setOnClickListener {
            if (!isTripActive) {
                startJourney()
            } else {
                endJourney()
            }
        }

        btnCenterMap.setOnClickListener {
            gpsTracker.currentLocation?.let { pt ->
                mapView.controller.animateTo(GeoPoint(pt.latitude, pt.longitude))
            }
        }

        btnTripHistory.setOnClickListener {
            showTripHistoryDialog()
        }

        gpsTracker.setListener(object : GpsTracker.Listener {
            override fun onLocationUpdate(point: LocationPoint, totalDistanceKm: Double) {
                runOnUiThread {
                    if (isTripActive) {
                        tvTripDistance.text = String.format(Locale.US, "Distance: %.2f km", totalDistanceKm)

                        val geoPoint = GeoPoint(point.latitude, point.longitude)
                        routePolyline.addPoint(geoPoint)

                        if (carMarker == null) {
                            carMarker = Marker(mapView).apply {
                                title = "Current Position"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                mapView.overlays.add(this)
                            }
                        }
                        carMarker?.position = geoPoint
                        mapView.controller.animateTo(geoPoint)
                        mapView.invalidate()
                    }
                }
            }
        })

        elmManager.setListener(object : Elm327Manager.Listener {
            override fun onConnecting() {
                runOnUiThread {
                    tvBtStatus.text = "🟡 Connecting..."
                    tvBtStatus.setBackgroundResource(R.drawable.bg_pill_disconnected)
                    tvBtStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.warning_amber))
                    btnQuickConnect.text = "CONNECTING..."
                    btnQuickConnect.isEnabled = false
                }
            }

            override fun onConnected(deviceName: String) {
                runOnUiThread {
                    tvBtStatus.text = "🟢 $deviceName"
                    tvBtStatus.setBackgroundResource(R.drawable.bg_pill_connected)
                    tvBtStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.grade_green))
                    btnQuickConnect.text = "DISCONNECT"
                    btnQuickConnect.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.danger_red))
                    btnQuickConnect.isEnabled = true
                    Toast.makeText(this@MainActivity, "Connected to $deviceName", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onDisconnected(message: String) {
                runOnUiThread {
                    updateSavedDeviceDisplay()
                    tvBtStatus.setBackgroundResource(R.drawable.bg_pill_disconnected)
                    tvBtStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.danger_red))
                    btnQuickConnect.text = "CONNECT"
                    btnQuickConnect.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.primary_blue))
                    btnQuickConnect.isEnabled = true
                }
            }

            override fun onTelemetry(sample: TelemetrySample) {
                runOnUiThread {
                    updateTelemetryUI(sample)
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    // Suppress transient hiccups
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun quickConnectSavedDevice() {
        val savedAddr = appPrefs.getString(KEY_LAST_DEVICE_ADDR, null)
        val adapter = BluetoothAdapter.getDefaultAdapter()

        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        if (savedAddr == null) {
            showBluetoothDevicePicker()
            return
        }

        val bonded = adapter.bondedDevices?.toList() ?: emptyList()
        val targetDevice = bonded.find { it.address == savedAddr }

        if (targetDevice != null) {
            elmManager.connect(targetDevice)
        } else {
            Toast.makeText(this, "Saved scanner not found in paired list. Select device.", Toast.LENGTH_SHORT).show()
            showBluetoothDevicePicker()
        }
    }

    private fun startJourney() {
        isTripActive = true
        currentTripId = UUID.randomUUID().toString()
        tripStartTimeMs = System.currentTimeMillis()
        maxRecordedSpeed = 0
        maxRecordedRpm = 0

        scoreEngine = DriverScoreEngine()
        routePolyline.actualPoints.clear()
        mapView.overlays.removeAll { it is Marker && it != carMarker }
        mapView.invalidate()

        gpsTracker.start()
        mainHandler.post(timerRunnable)

        btnStartEndTrip.text = "END JOURNEY"
        btnStartEndTrip.setBackgroundColor(ContextCompat.getColor(this, R.color.danger_red))
        Toast.makeText(this, "Journey started - Recording GPS & Telemetry", Toast.LENGTH_SHORT).show()
    }

    private fun endJourney() {
        if (!isTripActive) return

        isTripActive = false
        gpsTracker.stop()
        mainHandler.removeCallbacks(timerRunnable)

        btnStartEndTrip.text = "START JOURNEY"
        btnStartEndTrip.setBackgroundColor(ContextCompat.getColor(this, R.color.grade_green))

        val engine = scoreEngine ?: DriverScoreEngine()
        val report = engine.generateReport(gpsTracker.totalDistanceKm)
        val routePoints = gpsTracker.points

        // Save trip in SQLite
        currentTripId?.let { id ->
            dbHelper.saveTrip(id, tripStartTimeMs, report, routePoints)
            showTripSummaryDialog(id, report)
        }
    }

    private fun updateTelemetryUI(sample: TelemetrySample) {
        tvSpeed.text = sample.speedKmh.toString()
        maxRecordedSpeed = maxOf(maxRecordedSpeed, sample.speedKmh)
        tvMaxSpeed.text = "Max: $maxRecordedSpeed km/h"

        tvRpm.text = sample.rpm.toString()
        maxRecordedRpm = maxOf(maxRecordedRpm, sample.rpm)
        tvMaxRpm.text = "Max: $maxRecordedRpm rpm"

        // Redline warning color based on realistic RPM tiers
        when {
            sample.rpm >= 5000 -> tvRpm.setTextColor(ContextCompat.getColor(this, R.color.danger_red))
            sample.rpm >= 3500 -> tvRpm.setTextColor(ContextCompat.getColor(this, R.color.warning_amber))
            else -> tvRpm.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        }

        // Fuel Economy live display
        if (sample.instantKmPerLiter > 0.0) {
            tvFuelEconomy.text = String.format(Locale.US, "%.1f", sample.instantKmPerLiter)
        } else if (sample.speedKmh == 0) {
            tvFuelEconomy.text = "0.0"
        }

        tvEngineLoad.text = "${sample.engineLoadPct}%"
        tvCoolantTemp.text = if (sample.coolantTempC != 0) "Coolant: ${sample.coolantTempC}°C" else "Coolant: --°C"
        tvThrottle.text = "Throttle: ${sample.pedalPct}%"
        tvVoltage.text = String.format(Locale.US, "Battery: %.1fV", sample.batteryVoltage)

        // Process through scoring engine if trip is active
        if (isTripActive && scoreEngine != null) {
            val curLoc = gpsTracker.currentLocation
            val prevInfractionsCount = scoreEngine!!.allInfractions.size

            val score = scoreEngine!!.processCycle(
                speed = sample.speedKmh,
                rpm = sample.rpm,
                engineLoadPct = sample.engineLoadPct,
                coolantTempC = sample.coolantTempC,
                fuelEconomyKmL = sample.instantKmPerLiter,
                latitude = curLoc?.latitude,
                longitude = curLoc?.longitude
            )

            tvScoreNumber.text = score.toString()
            tvScoreGrade.text = "GRADE ${scoreEngine!!.currentGrade}"

            val infractions = scoreEngine!!.allInfractions
            tvInfractionsCount.text = "${infractions.size} Infractions"

            val avgKmL = scoreEngine!!.generateReport().stats.avgFuelEconomyKmL
            if (avgKmL > 0.0) {
                tvAvgFuelEconomy.text = String.format(Locale.US, "Trip Avg: %.1f km/L", avgKmL)
            }

            // Drop marker pin if new infraction occurred
            if (infractions.size > prevInfractionsCount && curLoc != null) {
                val latest = infractions.last()
                val infMarker = Marker(mapView).apply {
                    position = GeoPoint(curLoc.latitude, curLoc.longitude)
                    title = "${latest.title} (-${latest.penalty})"
                    snippet = latest.details
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                mapView.overlays.add(infMarker)
                mapView.invalidate()
                Toast.makeText(this, "Infraction: ${latest.title}", Toast.LENGTH_SHORT).show()
            }

            // Update score color & description
            when {
                score >= 85 -> {
                    tvScoreNumber.setTextColor(ContextCompat.getColor(this, R.color.grade_green))
                    tvScoreGrade.setTextColor(ContextCompat.getColor(this, R.color.grade_green))
                    tvScoreStatus.text = "Safe & Efficient Driving"
                }
                score >= 70 -> {
                    tvScoreNumber.setTextColor(ContextCompat.getColor(this, R.color.warning_amber))
                    tvScoreGrade.setTextColor(ContextCompat.getColor(this, R.color.warning_amber))
                    tvScoreStatus.text = "Moderate Driving Behavior"
                }
                else -> {
                    tvScoreNumber.setTextColor(ContextCompat.getColor(this, R.color.danger_red))
                    tvScoreGrade.setTextColor(ContextCompat.getColor(this, R.color.danger_red))
                    tvScoreStatus.text = "Aggressive Driving Detected"
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothDevicePicker() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }

        if (!adapter.isEnabled) {
            Toast.makeText(this, "Please turn on Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }

        val bonded = adapter.bondedDevices?.toList() ?: emptyList()
        if (bonded.isEmpty()) {
            Toast.makeText(this, "No paired Bluetooth devices found. Pair with ELM327 in Android Settings first.", Toast.LENGTH_LONG).show()
            return
        }

        val deviceNames = bonded.map { "${it.name ?: "Unknown"} (${it.address})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Paired OBD-II Scanner")
            .setItems(deviceNames) { _, which ->
                val selectedDevice = bonded[which]
                // Save to SharedPreferences for quick automatic reconnect
                appPrefs.edit()
                    .putString(KEY_LAST_DEVICE_ADDR, selectedDevice.address)
                    .putString(KEY_LAST_DEVICE_NAME, selectedDevice.name ?: selectedDevice.address)
                    .apply()

                updateSavedDeviceDisplay()
                elmManager.connect(selectedDevice)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTripSummaryDialog(tripId: String, report: ScoreReport) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_trip_summary, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val tvScore = dialogView.findViewById<TextView>(R.id.dialogTvScore)
        val tvGrade = dialogView.findViewById<TextView>(R.id.dialogTvGrade)
        val tvDistance = dialogView.findViewById<TextView>(R.id.dialogTvDistance)
        val tvDuration = dialogView.findViewById<TextView>(R.id.dialogTvDuration)
        val tvMaxSpeed = dialogView.findViewById<TextView>(R.id.dialogTvMaxSpeed)
        val tvMaxRpm = dialogView.findViewById<TextView>(R.id.dialogTvMaxRpm)
        val tvIdleTime = dialogView.findViewById<TextView>(R.id.dialogTvIdleTime)
        val tvFuelEco = dialogView.findViewById<TextView>(R.id.dialogTvFuelEconomy)
        val tvInfractions = dialogView.findViewById<TextView>(R.id.dialogTvInfractions)
        val tvInfractionsList = dialogView.findViewById<TextView>(R.id.dialogTvInfractionsList)
        val btnExportCsv = dialogView.findViewById<Button>(R.id.dialogBtnExportCsv)
        val btnClose = dialogView.findViewById<Button>(R.id.dialogBtnClose)

        tvScore.text = report.score.toString()
        tvGrade.text = "GRADE ${report.grade}"
        tvDistance.text = String.format(Locale.US, "Distance: %.2f km", report.stats.distanceKm)

        val mm = report.stats.durationSeconds / 60
        val ss = report.stats.durationSeconds % 60
        tvDuration.text = "Duration: ${mm}m ${ss}s"

        tvMaxSpeed.text = "Max Speed: ${report.stats.maxSpeedKmh} km/h"
        tvMaxRpm.text = "Max RPM: ${report.stats.maxRpm}"
        tvIdleTime.text = "Idling: ${report.stats.idleSeconds}s"
        tvFuelEco.text = String.format(Locale.US, "Avg Fuel: %.1f km/L", report.stats.avgFuelEconomyKmL)
        tvInfractions.text = "Infractions: ${report.infractions.size}"

        if (report.infractions.isNotEmpty()) {
            val sb = StringBuilder()
            report.infractions.forEachIndexed { i, inf ->
                sb.append("${i + 1}. ${inf.title} (-${inf.penalty} pts)\n   ${inf.details} at ${inf.speed} km/h\n")
            }
            tvInfractionsList.text = sb.toString().trim()
        } else {
            tvInfractionsList.text = "No infractions recorded! Smooth and efficient journey."
        }

        btnExportCsv.setOnClickListener {
            shareTripCsv(tripId)
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showTripHistoryDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_trip_history, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.historyContainer)
        val btnClose = dialogView.findViewById<Button>(R.id.btnHistoryClose)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        fun refreshList() {
            container.removeAllViews()
            val trips = dbHelper.getAllTrips()

            if (trips.isEmpty()) {
                val emptyTv = TextView(this).apply {
                    text = "No saved trips yet. Complete a journey to view history."
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    setPadding(16, 24, 16, 24)
                }
                container.addView(emptyTv)
            } else {
                val dateFormat = SimpleDateFormat("MMM dd, yyyy - hh:mm a", Locale.getDefault())
                for (trip in trips) {
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
                        setPadding(20, 16, 20, 16)
                        val params = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        params.bottomMargin = 16
                        layoutParams = params
                    }

                    val header = TextView(this).apply {
                        text = dateFormat.format(Date(trip.startTime))
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                        textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }

                    val stats = TextView(this).apply {
                        val mm = trip.durationSeconds / 60
                        val ss = trip.durationSeconds % 60
                        val fuelStr = if (trip.avgFuelEconomyKmL > 0) String.format(Locale.US, " • %.1f km/L", trip.avgFuelEconomyKmL) else ""
                        text = String.format(
                            Locale.US,
                            "Score: %d (%s)  •  %.2f km  •  %02dm %02ds%s  •  %d Infractions",
                            trip.score, trip.grade, trip.distanceKm, mm, ss, fuelStr, trip.infractionCount
                        )
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                        textSize = 12f
                        setPadding(0, 4, 0, 8)
                    }

                    // Action buttons row: Share CSV and Delete
                    val actionRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.END
                    }

                    val btnShare = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                        text = "Share CSV"
                        textSize = 11f
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary_blue))
                        setOnClickListener {
                            shareTripCsv(trip.id)
                        }
                    }

                    val btnDelete = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                        text = "Delete"
                        textSize = 11f
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.danger_red))
                        setOnClickListener {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Delete Trip")
                                .setMessage("Are you sure you want to delete this trip record and route data?")
                                .setPositiveButton("Delete") { _, _ ->
                                    dbHelper.deleteTrip(trip.id)
                                    refreshList()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }

                    actionRow.addView(btnShare)
                    actionRow.addView(btnDelete)

                    card.addView(header)
                    card.addView(stats)
                    card.addView(actionRow)
                    container.addView(card)
                }
            }
        }

        refreshList()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun shareTripCsv(tripId: String) {
        try {
            val csv = dbHelper.generateTripCsv(tripId)
            val exportsDir = File(cacheDir, "exports")
            if (!exportsDir.exists()) exportsDir.mkdirs()

            val csvFile = File(exportsDir, "fleetpulse_trip_${tripId.take(8)}.csv")
            csvFile.writeText(csv)

            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                csvFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "FleetPulse Trip Route CSV")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Trip Route CSV"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to share CSV: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        elmManager.disconnect()
        gpsTracker.stop()
        mainHandler.removeCallbacks(timerRunnable)
    }
}
