package com.docunest.camper.victron

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONObject
import java.util.Locale

/**
 * Victron Bluetooth "Instant Readout": listens for the encrypted adverts of the MPPT and the Blue Smart charger
 * (no connection, so VictronConnect and the heater's single GATT link are unaffected). Read-only.
 * Devices come from camper.env: VICTRON_<NAME>=<MAC>,<32 hex key>, e.g. VICTRON_MPPT, VICTRON_CHARGER.
 */
class Victron(ctx: Context, devices: Map<String, Pair<String, String>>) {
    companion object {
        const val TAG = "Camper"
        const val STALE_MS = 180_000L
    }

    private class Dev(val name: String, val key: ByteArray) {
        @Volatile var values: JSONObject? = null
        @Volatile var seen = 0L
        @Volatile var rssi = 0
        @Volatile var error: String? = null
    }

    private val byMac = devices.mapNotNull { (name, v) ->
        val key = runCatching { v.second.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
        if (key?.size == 16) v.first.uppercase(Locale.US) to Dev(name, key) else null
    }.toMap()
    private val adapter: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val h = Handler(HandlerThread("victron").apply { start() }.looper)
    @Volatile private var scanning = false

    val configured get() = byMac.isNotEmpty()

    fun start() { if (configured) h.post { scan() } }

    private fun scan() {
        val s = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (s == null) { h.postDelayed({ scan() }, 30_000); return }
        val filter = ScanFilter.Builder().setManufacturerData(VictronParser.VICTRON_COMPANY_ID, byteArrayOf()).build()
        runCatching {
            if (scanning) s.stopScan(cb)
            s.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), cb)
            scanning = true
        }.onFailure { Log.w(TAG, "victron scan", it) }
        // Android stops long-running unfiltered scans and some stacks drop filtered ones; restart every 10 min
        h.postDelayed({ scan() }, 600_000)
    }

    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val m = r.scanRecord?.getManufacturerSpecificData(VictronParser.VICTRON_COMPANY_ID) ?: return
            val d = byMac[r.device.address.uppercase(Locale.US)] ?: return
            d.rssi = r.rssi
            try {
                val v = VictronParser.parse(m, d.key)
                if (v == null) d.error = "unsupported record type ${VictronParser.recordType(m)}"
                else { d.values = v; d.seen = System.currentTimeMillis(); d.error = null }
            } catch (_: VictronParser.KeyMismatch) {
                d.error = "encryption key does not match"
            } catch (e: Exception) {
                d.error = "decode failed: $e"
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "victron scan failed $errorCode")
            scanning = false
            h.postDelayed({ scan() }, 30_000)
        }
    }

    /** Fresh values of one device, or null when not heard from for 3 min. */
    fun values(name: String): JSONObject? = byMac.values.firstOrNull { it.name == name }
        ?.takeIf { System.currentTimeMillis() - it.seen < STALE_MS }?.values

    /** {mppt: {...values, age_s, rssi} | {error}, charger: ...} for the snapshot. */
    fun json(): JSONObject {
        val now = System.currentTimeMillis()
        val out = JSONObject()
        for (d in byMac.values) {
            val v = d.values
            out.put(d.name, if (v != null && now - d.seen < STALE_MS) JSONObject(v.toString()).put("age_s", (now - d.seen) / 1000).put("rssi", d.rssi)
                else JSONObject().put("error", d.error ?: if (d.seen == 0L) "not heard yet" else "not heard for ${(now - d.seen) / 60_000} min"))
        }
        return out
    }
}
