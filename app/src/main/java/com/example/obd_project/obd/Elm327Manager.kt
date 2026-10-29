package com.example.obd_project.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class Elm327Manager {

    companion object {
        private const val TAG = "Elm327Manager"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TIMEOUT_MS = 2500L
    }

    interface Listener {
        fun onConnecting()
        fun onConnected(deviceName: String)
        fun onDisconnected(message: String)
        fun onTelemetry(sample: TelemetrySample)
        fun onError(error: String)
    }

    private var listener: Listener? = null
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null

    fun setListener(l: Listener?) {
        this.listener = l
    }

    val isConnected: Boolean
        get() = isRunning.get() && socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()

        workerThread = Thread({
            try {
                listener?.onConnecting()
                Log.d(TAG, "Creating RFCOMM socket to ${device.name} [${device.address}]")

                val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()

                sock.connect()
                socket = sock
                inputStream = sock.inputStream
                outputStream = sock.outputStream
                isRunning.set(true)

                Log.d(TAG, "Connected to ${device.name}. Initializing ELM327...")

                if (!initElm327()) {
                    disconnect()
                    listener?.onDisconnected("Failed to initialize ELM327 protocol")
                    return@Thread
                }

                listener?.onConnected(device.name ?: device.address)
                pollLoop()

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}", e)
                disconnect()
                listener?.onDisconnected("Connection error: ${e.localizedMessage ?: "Unknown"}")
            }
        }, "ELM327-Worker").apply {
            isDaemon = true
            start()
        }
    }

    fun disconnect() {
        isRunning.set(false)
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        inputStream = null
        outputStream = null
        workerThread?.interrupt()
        workerThread = null
    }

    private fun sendCommand(cmd: String): String {
        val out = outputStream ?: throw IllegalStateException("Not connected")
        val input = inputStream ?: throw IllegalStateException("Not connected")

        val commandBytes = (cmd.trim() + "\r").toByteArray(Charsets.US_ASCII)
        out.write(commandBytes)
        out.flush()

        val sb = StringBuilder()
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < TIMEOUT_MS) {
            if (input.available() > 0) {
                val b = input.read()
                if (b == -1) break
                val c = b.toChar()
                if (c == '>') {
                    break // ELM327 prompt reached
                }
                sb.append(c)
            } else {
                Thread.sleep(10)
            }
        }

        return sb.toString().trim()
    }

    private fun initElm327(): Boolean {
        return try {
            Thread.sleep(500)
            sendCommand("ATZ") // Reset
            Thread.sleep(800)
            sendCommand("ATE0") // Echo off
            sendCommand("ATL0") // Linefeeds off
            sendCommand("ATS0") // Spaces off
            sendCommand("ATH0") // Headers off
            sendCommand("ATSP0") // Auto protocol detect
            sendCommand("ATAT2") // Adaptive timing
            true
        } catch (e: Exception) {
            Log.e(TAG, "Init error: ${e.message}")
            false
        }
    }

    private fun pollLoop() {
        var currentSpeed = 0
        var currentRpm = 0
        var currentLoad = 0
        var currentPedal = 0
        var currentCoolant = 0
        var currentVoltage = 12.6
        var currentMaf = 0.0

        var lastMediumLoop = 0L

        while (isRunning.get() && socket?.isConnected == true) {
            try {
                // Fast Loop: Speed
                val speedResp = sendCommand("010D")
                ObdProtocol.decodeSpeed(speedResp)?.let { currentSpeed = it }

                // Fast Loop: RPM
                val rpmResp = sendCommand("010C")
                ObdProtocol.decodeRpm(rpmResp)?.let { currentRpm = it }

                // Fast Loop: Load
                val loadResp = sendCommand("0104")
                ObdProtocol.decodeEngineLoad(loadResp)?.let { currentLoad = it }

                // Fast Loop: Pedal Position (may be unsupported on older cars)
                val pedalResp = sendCommand("0149")
                ObdProtocol.decodePedalPosition(pedalResp)?.let { currentPedal = it }

                // Medium Loop (Every ~3 seconds)
                val now = System.currentTimeMillis()
                if (now - lastMediumLoop > 3000L) {
                    val coolantResp = sendCommand("0105")
                    ObdProtocol.decodeCoolantTemp(coolantResp)?.let { currentCoolant = it }

                    val voltResp = sendCommand("ATRV")
                    ObdProtocol.decodeVoltage(voltResp)?.let { currentVoltage = it }

                    val mafResp = sendCommand("0110")
                    ObdProtocol.decodeMaf(mafResp)?.let { currentMaf = it }

                    lastMediumLoop = now
                }

                val instantKmL = ObdProtocol.calculateFuelEconomyKmL(
                    speedKmh = currentSpeed,
                    mafGps = currentMaf,
                    rpm = currentRpm,
                    engineLoadPct = currentLoad
                )

                val sample = TelemetrySample(
                    speedKmh = currentSpeed,
                    rpm = currentRpm,
                    engineLoadPct = currentLoad,
                    pedalPct = currentPedal,
                    coolantTempC = currentCoolant,
                    batteryVoltage = currentVoltage,
                    mafGps = currentMaf,
                    instantKmPerLiter = instantKmL,
                    timestamp = System.currentTimeMillis()
                )

                listener?.onTelemetry(sample)

                // Brief yield to avoid choking ELM327 bus
                Thread.sleep(50)

            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Polling error: ${e.message}")
                    listener?.onError("Read error: ${e.localizedMessage}")
                }
                break
            }
        }

        if (isRunning.get()) {
            disconnect()
            listener?.onDisconnected("Connection closed by peer")
        }
    }
}
