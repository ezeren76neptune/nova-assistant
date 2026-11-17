package app.nova.assistant.tools

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.ToolOutcome
import kotlinx.coroutines.delay

/** Battery of Bluetooth accessories: AirPods (read from their own BLE broadcast) and other connected devices. */
object BluetoothTools {
    private const val APPLE = 0x004C

    data class Pods(val left: Int?, val right: Int?, val case: Int?, val chargingL: Boolean, val chargingR: Boolean, val chargingCase: Boolean, val rssi: Int) {
        fun describe(): String {
            fun p(v: Int?, charging: Boolean) = v?.let { "$it%" + if (charging) " (charging)" else "" } ?: "not in range"
            val buds = if (left != null && right != null && left == right && chargingL == chargingR) "both earbuds ${p(left, chargingL)}"
            else "left ${p(left, chargingL)}, right ${p(right, chargingR)}"
            return "AirPods: $buds" + (case?.let { ", case ${p(it, chargingCase)}" } ?: "")
        }
    }

    /**
     * AirPods/Beats announce their battery in Apple's "proximity pairing" advertisement (manufacturer data type 0x07)
     * — the same signal iPhones use for their pop-up. Layout per OpenPods: nibbles 12/13 = buds (swapped when
     * flipped), 14 = charging flags, 15 = case; values 0–10 are tens of percent, 15 = not available.
     */
    fun parsePods(data: ByteArray, rssi: Int): Pods? {
        if (data.size != 27 || data[0] != 0x07.toByte()) return null
        val hex = data.joinToString("") { "%02X".format(it) }
        fun n(i: Int) = hex[i].digitToInt(16)
        val flip = (n(10) and 0x02) == 0
        val l = n(if (flip) 12 else 13)
        val r = n(if (flip) 13 else 12)
        val c = n(15)
        val ch = n(14)
        fun pct(v: Int) = if (v in 0..10) v * 10 else null
        return Pods(
            pct(l), pct(r), pct(c),
            chargingL = ch and (if (flip) 0b0010 else 0b0001) != 0,
            chargingR = ch and (if (flip) 0b0001 else 0b0010) != 0,
            chargingCase = ch and 0b0100 != 0,
            rssi = rssi,
        )
    }

    /** Listens for nearby AirPods for up to [ms]; returns the closest one (strongest signal). */
    @SuppressLint("MissingPermission")
    suspend fun scanPods(adapter: BluetoothAdapter, ms: Long = 4500): Pods? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        var best: Pods? = null
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getManufacturerSpecificData(APPLE) ?: return
                val p = parsePods(data, result.rssi) ?: return
                if (best == null || p.rssi > best!!.rssi) best = p
            }
        }
        val filter = ScanFilter.Builder().setManufacturerData(APPLE, byteArrayOf(0x07, 0x19), byteArrayOf(-1, -1)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, cb)
            val end = System.currentTimeMillis() + ms
            // stop early once a close, complete reading arrives
            while (System.currentTimeMillis() < end) {
                delay(250)
                best?.let { if (it.rssi > -65 && (it.left != null || it.right != null)) return it }
            }
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
        // ignore other people's AirPods far away
        return best?.takeIf { it.rssi > -80 }
    }

    /** Connected Bluetooth devices that report battery (headphones, watches…). Uses Android's hidden getters. */
    @SuppressLint("MissingPermission")
    fun connectedBatteries(adapter: BluetoothAdapter): List<Pair<String, Int>> = adapter.bondedDevices.orEmpty().mapNotNull { d ->
        val connected = runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false)
        if (!connected) return@mapNotNull null
        val level = runCatching { d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int }.getOrDefault(-1)
        if (level in 0..100) ((if (Build.VERSION.SDK_INT >= 30) d.alias else null) ?: d.name ?: "Bluetooth device") to level else null
    }

    val tools = listOf(
        Tool(
            "device_battery", "device?:str e.g. airpods, headphones, watch",
            "Battery of Bluetooth accessories like AirPods, earbuds, headphones or a watch (NOT the phone's own battery)",
            "Device", "Checking…",
        ) { a ->
            val want = a.str("device", "name", "accessory")?.lowercase()
            val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            requirePerms(*perms, why = "read your Bluetooth devices' battery")?.let { return@Tool it }
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return@Tool fail("This phone doesn't have Bluetooth.")
            if (!adapter.isEnabled) return@Tool fail("Bluetooth is off, so I can't see your devices.")
            val lines = mutableListOf<String>()
            val podsWanted = want == null || listOf("airpod", "pods", "beats", "ear", "bud", "apple").any { want.contains(it) }
            if (podsWanted) {
                runCatching { scanPods(adapter) }.onFailure { AppLog.d("bt", "AirPods scan failed", it) }.getOrNull()
                    ?.let { lines += it.describe() }
            }
            connectedBatteries(adapter)
                .filter { (name, _) -> want == null || podsWanted || name.lowercase().contains(want.removePrefix("my ").trim()) }
                .forEach { (name, lvl) -> if (lines.none { it.startsWith("AirPods") && name.contains("AirPods", true) }) lines += "$name: $lvl%" }
            if (lines.isEmpty()) {
                return@Tool fail(
                    if (podsWanted) "I couldn't hear your AirPods. Open the case near the phone, or put them in, and ask again."
                    else "None of your connected Bluetooth devices report their battery."
                )
            }
            ok(lines.joinToString(". ") + ".")
        },
    )
}
