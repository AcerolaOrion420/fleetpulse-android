package com.example.obd_project.obd

data class TelemetrySample(
    val speedKmh: Int = 0,
    val rpm: Int = 0,
    val engineLoadPct: Int = 0,
    val pedalPct: Int = 0,
    val coolantTempC: Int = 0,
    val batteryVoltage: Double = 0.0,
    val mafGps: Double = 0.0,
    val instantKmPerLiter: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis()
)

object ObdProtocol {
    private val ERROR_TOKENS = listOf(
        "NO DATA", "UNABLE TO CONNECT", "BUS INIT", "BUS ERROR",
        "STOPPED", "CAN ERROR", "BUFFER FULL", "?", "SEARCHING...",
        "FB ERROR", "DATA ERROR", "ERR"
    )

    fun isErrorResponse(raw: String): Boolean {
        val trimmed = raw.trim().uppercase()
        return ERROR_TOKENS.any { trimmed.contains(it) }
    }

    /**
     * Parses an OBD-II response string for a given mode and PID.
     * Expects standard response format: e.g. "41 0C 1A F8" or "410C1AF8"
     */
    fun parseResponse(raw: String, expectedPid: String): List<Int>? {
        val clean = raw.replace("\r", "")
            .replace("\n", "")
            .replace(">", "")
            .replace(" ", "")
            .trim()
            .uppercase()

        if (isErrorResponse(clean)) return null

        val expectedHeader = "41" + expectedPid.uppercase()
        val index = clean.indexOf(expectedHeader)
        if (index == -1) return null

        val dataHex = clean.substring(index + expectedHeader.length)
        if (dataHex.length < 2) return null

        val bytes = mutableListOf<Int>()
        var i = 0
        while (i + 1 < dataHex.length) {
            val byteStr = dataHex.substring(i, i + 2)
            val byteVal = byteStr.toIntOrNull(16) ?: break
            bytes.add(byteVal)
            i += 2
        }

        return if (bytes.isNotEmpty()) bytes else null
    }

    fun decodeRpm(raw: String): Int? {
        val bytes = parseResponse(raw, "0C") ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 4
    }

    fun decodeSpeed(raw: String): Int? {
        val bytes = parseResponse(raw, "0D") ?: return null
        if (bytes.isEmpty()) return null
        return bytes[0]
    }

    fun decodeEngineLoad(raw: String): Int? {
        val bytes = parseResponse(raw, "04") ?: return null
        if (bytes.isEmpty()) return null
        return Math.round((bytes[0] * 100f) / 255f)
    }

    fun decodePedalPosition(raw: String): Int? {
        val bytes = parseResponse(raw, "49") ?: return null
        if (bytes.isEmpty()) return null
        return Math.round((bytes[0] * 100f) / 255f)
    }

    fun decodeCoolantTemp(raw: String): Int? {
        val bytes = parseResponse(raw, "05") ?: return null
        if (bytes.isEmpty()) return null
        return bytes[0] - 40
    }

    fun decodeMaf(raw: String): Double? {
        // Mode 01 PID 10: 2 bytes, ((A * 256) + B) / 100.0 (g/s)
        val bytes = parseResponse(raw, "10") ?: return null
        if (bytes.size < 2) return null
        val maf = ((bytes[0] * 256) + bytes[1]) / 100.0
        return Math.round(maf * 100.0) / 100.0
    }

    fun decodeVoltage(raw: String): Double? {
        // Mode 01 PID 42
        val bytes = parseResponse(raw, "42")
        if (bytes != null && bytes.size >= 2) {
            val mv = (bytes[0] * 256) + bytes[1]
            return Math.round((mv / 1000.0) * 10.0) / 10.0
        }
        // Direct ELM command "ATRV" returns e.g. "12.6V"
        val clean = raw.replace("\r", "").replace("\n", "").replace(">", "").trim()
        if (clean.endsWith("V", ignoreCase = true)) {
            val num = clean.dropLast(1).trim().toDoubleOrNull()
            if (num != null) return num
        }
        return null
    }

    /**
     * Calculates instantaneous Fuel Economy in km/L based on MAF or Speed-Density estimation.
     */
    fun calculateFuelEconomyKmL(speedKmh: Int, mafGps: Double, rpm: Int, engineLoadPct: Int): Double {
        val effectiveMaf = if (mafGps > 0.1) {
            mafGps
        } else if (rpm > 350 && engineLoadPct > 0) {
            // Speed-Density approximation for standard ~1.2L engine displacement
            // MAF (g/s) ≈ (RPM * 1.2 * Load% / 12000)
            (rpm.toDouble() * 1.2 * engineLoadPct.toDouble()) / 12000.0
        } else {
            0.0
        }

        if (effectiveMaf <= 0.05) return 0.0

        // Petrol Stoichiometric: 14.7 AFR, density ~820 g/L -> ~12,054 g air per liter of fuel
        // Fuel flow (Liters/hour) = (MAF * 3600) / 12054
        val litersPerHour = (effectiveMaf * 3600.0) / 12054.0
        if (litersPerHour <= 0.05) return 0.0

        if (speedKmh <= 1) return 0.0 // stationary (infinite L/100km or 0 km/L)

        val kmL = speedKmh / litersPerHour
        return Math.round(kmL.coerceIn(0.0, 50.0) * 10.0) / 10.0
    }
}
