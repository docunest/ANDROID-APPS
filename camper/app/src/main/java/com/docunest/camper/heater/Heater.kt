package com.docunest.camper.heater

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * Vevor / "BYD-xxxx" diesel heater (AirHeaterBLE app) over Bluetooth LE.
 * Port of docunest/BatteryPro diesel-heater/bridge/heaterbridge/protocol.py (community reverse engineering,
 * Spettacolo83/diesel-heater-ble, MIT). One GATT characteristic FFE1 (service FFE0) for commands and status.
 * Commands: AA 55 <pk/100> <pk%100> <cmd> <arg lo> <arg hi> <sum(2..6)>, unencrypted.
 */
object HeaterProtocol {
    val SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    val CHAR: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val STATUS = 1
    const val SET_MODE = 2
    const val POWER = 3
    const val SET_TARGET = 4
    const val MODE_LEVEL = 1
    const val MODE_TEMPERATURE = 2
    private val XOR_KEY = "password".toByteArray()

    private val STEPS = mapOf(0 to "standby", 1 to "self-test", 2 to "ignition", 3 to "running", 4 to "cooldown", 6 to "ventilation")
    private val ERRORS = mapOf(1 to "E1 startup failure", 2 to "E2 lack of fuel", 3 to "E3 supply voltage", 4 to "E4 outlet sensor",
        5 to "E5 inlet sensor", 6 to "E6 fuel pump", 7 to "E7 fan", 8 to "E8 ignition unit", 9 to "E9 overheating", 10 to "E10 overheat sensor")

    fun command(cmd: Int, arg: Int = 0, passkey: Int = 1234): ByteArray {
        val p = IntArray(8)
        p[0] = 0xAA; p[1] = 0x55
        p[2] = (passkey / 100) % 256; p[3] = passkey % 100
        p[4] = cmd % 256; p[5] = arg % 256; p[6] = (arg / 256) % 256
        p[7] = (p[2] + p[3] + p[4] + p[5] + p[6]) % 256
        return ByteArray(8) { p[it].toByte() }
    }

    fun level(level: Int, passkey: Int = 1234): ByteArray {
        require(level in 1..10) { "level must be 1..10" }
        return command(SET_TARGET, level, passkey)
    }

    fun xor(data: ByteArray): ByteArray = ByteArray(data.size) { i ->
        if (i < 48) (data[i].toInt() xor XOR_KEY[i % 8].toInt()).toByte() else data[i]
    }

    data class Status(
        val protocol: String, val running: Boolean, val step: String, val mode: String, val level: Int?,
        val setTemp: Int?, val roomTemp: Double?, val caseTemp: Double?, val voltage: Double?, val altitude: Double?,
        val errorCode: Int, val error: String?,
    ) {
        val heating get() = step == "ignition" || step == "running"
        fun json(): JSONObject = JSONObject().put("protocol", protocol).put("running", running).put("step", step).put("mode", mode)
            .put("level", level ?: JSONObject.NULL).put("set_temp", setTemp ?: JSONObject.NULL)
            .put("room_temp", roomTemp ?: JSONObject.NULL).put("case_temp", caseTemp ?: JSONObject.NULL)
            .put("voltage", voltage ?: JSONObject.NULL).put("error_code", errorCode).put("error", error ?: JSONObject.NULL)
            .put("heating", heating)
    }

    private fun s16(v: Int) = if (v >= 0x8000) v - 0x10000 else v
    private fun clamp(v: Int) = v.coerceIn(1, 10)
    private fun modeName(m: Int) = when (m) { 0 -> "manual"; 1 -> "level"; 2 -> "temperature"; else -> "unknown($m)" }
    private fun step(v: Int) = STEPS[v] ?: "unknown($v)"
    private fun err(v: Int) = if (v == 0) null else ERRORS[v] ?: "E$v"

    fun parse(raw: ByteArray): Status? {
        if (raw.size < 17) return null
        val u = raw.map { it.toInt() and 0xFF }
        val header = (u[0] shl 8) or u[1]
        if ((header == 0xAA55 || header == 0xAA66) && raw.size < 40) return plain(u, if (header == 0xAA55) "AA55" else "AA66")
        if (raw.size >= 40) {
            val d = xor(raw).map { it.toInt() and 0xFF }
            val h = (d[0] shl 8) or d[1]
            if (h == 0xAA55 || h == 0xAA66) return encrypted(d, if (h == 0xAA55) "AA55enc" else "AA66enc")
        }
        return null
    }

    private fun plain(d: List<Int>, proto: String): Status {
        val mode = d[8]
        var setTemp: Int? = null
        val level = when (mode) {
            MODE_LEVEL -> clamp(d[9])
            MODE_TEMPERATURE -> { setTemp = d[9]; clamp(d[10] + 1) }
            else -> clamp(d[10] + 1)
        }
        val e: Int
        val case: Double
        if (proto == "AA66") {
            e = if (d.size > 17) d[17] else 0
            val raw = d[13] or (d[14] shl 8)
            case = if (raw > 350) raw / 10.0 else raw.toDouble()
        } else {
            e = d[4]
            case = s16(d[13] or (d[14] shl 8)).toDouble()
        }
        return Status(proto, d[3] == 1, step(d[5]), modeName(mode), level, setTemp, s16(d[15] or (d[16] shl 8)).toDouble(), case,
            (d[11] or (d[12] shl 8)) / 10.0, (d[6] or (d[7] shl 8)).toDouble(), e, err(e))
    }

    private fun encrypted(d: List<Int>, proto: String): Status {
        val mode = d[8]
        val e = if (proto == "AA66enc") d[35] else d[4]
        var setTemp = d[9]
        if (proto == "AA66enc" && d[27] == 1) setTemp = Math.round((setTemp - 32) * 5 / 9.0).toInt()
        return Status(proto, d[3] == 1, step(d[5]), modeName(mode), clamp(d[10]), if (mode == MODE_TEMPERATURE) setTemp else null,
            s16((d[32] shl 8) or d[33]) / 10.0, s16((d[13] shl 8) or d[14]).toDouble(), ((d[11] shl 8) or d[12]) / 10.0,
            ((d[6] shl 8) or d[7]) / 10.0, e, err(e))
    }
}

/**
 * Keeps one BLE connection to the heater: scan for BYD-*, connect, enable notifications, poll status every 5 s,
 * reconnect on any drop. The heater accepts ONE connection: the AirHeaterBLE app or the web app must be closed.
 */
@SuppressLint("MissingPermission")
class HeaterBle(private val ctx: Context, private val passkey: Int = 1234, private val namePrefix: String = "BYD") {
    @Volatile var status: HeaterProtocol.Status? = null
    @Volatile var lastUpdate = 0L
    @Volatile var connected = false
    @Volatile var deviceName: String? = null
    @Volatile var lastError: String? = null
    @Volatile var address: String? = null

    private val thread = HandlerThread("camper-heater").apply { start() }
    private val h = Handler(thread.looper)
    private var gatt: BluetoothGatt? = null
    private var chr: BluetoothGattCharacteristic? = null
    private var scanning = false
    private val writes = LinkedBlockingQueue<ByteArray>()
    private var writing = false

    private val adapter: BluetoothAdapter? get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    fun start() = h.post { connectOrScan() }

    fun power(on: Boolean) = enqueue(HeaterProtocol.command(HeaterProtocol.POWER, if (on) 1 else 0, passkey), poll = true)

    fun setLevel(level: Int) {
        if (status?.mode != "level") enqueue(HeaterProtocol.command(HeaterProtocol.SET_MODE, HeaterProtocol.MODE_LEVEL, passkey))
        enqueue(HeaterProtocol.level(level, passkey), poll = true)
    }

    private fun enqueue(pkt: ByteArray, poll: Boolean = false) {
        if (!connected) throw IllegalStateException("diesel heater not connected")
        writes.add(pkt)
        if (poll) writes.add(HeaterProtocol.command(HeaterProtocol.STATUS, 0, passkey))
        h.post { pump() }
    }

    // the controller drops back-to-back writes: one at a time, 300 ms apart
    private fun pump() {
        if (writing) return
        val g = gatt; val c = chr
        val pkt = writes.poll() ?: return
        if (g == null || c == null) { writes.clear(); return }
        writing = true
        c.writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION")
        c.value = pkt
        @Suppress("DEPRECATION")
        val ok = g.writeCharacteristic(c)
        h.postDelayed({ writing = false; if (!ok) Log.w(TAG, "heater write failed"); pump() }, 300)
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (connected) {
                writes.add(HeaterProtocol.command(HeaterProtocol.STATUS, 0, passkey)); pump()
                // no status for 60 s while "connected": treat the link as dead
                if (lastUpdate > 0 && System.currentTimeMillis() - lastUpdate > 60_000) reconnect("status timeout")
            }
            h.postDelayed(this, 5000)
        }
    }

    private fun connectOrScan() {
        val a = adapter
        if (a == null || !a.isEnabled) { lastError = "Bluetooth is off"; h.postDelayed({ connectOrScan() }, 10_000); return }
        val known = address
        if (known != null) { connect(a.getRemoteDevice(known)); return }
        startScan()
    }

    private fun startScan() {
        val s = adapter?.bluetoothLeScanner ?: run { h.postDelayed({ connectOrScan() }, 10_000); return }
        if (scanning) return
        scanning = true
        lastError = "searching for $namePrefix-…"
        runCatching { s.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb) }
            .onFailure { scanning = false; lastError = "scan failed: ${it.message}" }
        seen.clear()
        h.postDelayed({
            if (scanning) {
                stopScan()
                val names = seen.values.distinct().take(6).joinToString(", ").ifEmpty { "nothing" }
                Log.i(TAG, "heater scan: no name match; saw ${heard.size} devices: $names")
                // some units advertise without their BYD name: try connectable, unnamed, non-Apple/Victron devices
                probeQueue.clear()
                probeQueue.addAll(heard.values.filter { d ->
                    d.optBoolean("connectable") && d.isNull("name") && d.getString("address") !in probed &&
                        (0 until d.getJSONArray("makers").length()).none { d.getJSONArray("makers").getString(it) in setOf("004c", "02e1") }
                }.sortedByDescending { it.optInt("rssi") }.map { it.getString("address") })
                if (probeQueue.isNotEmpty()) probeNext()
                else {
                    lastError = "not found, is a phone app connected to it? (sees: $names)"
                    h.postDelayed({ connectOrScan() }, 20_000)
                }
            }
        }, 15_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
    }

    private val seen = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val probeQueue = ArrayDeque<String>()
    /** Addresses already checked and found not to be a heater (not retried until restart). */
    private val probed = mutableSetOf<String>()
    @Volatile private var probing: String? = null

    private fun probeNext() {
        val addr = probeQueue.removeFirstOrNull() ?: run {
            lastError = "not found, is a phone app connected to it?"
            h.postDelayed({ connectOrScan() }, 20_000); return
        }
        probing = addr
        lastError = "checking $addr…"
        Log.i(TAG, "heater probe $addr")
        connect(adapter!!.getRemoteDevice(addr))
        h.postDelayed({ if (probing == addr && !connected) { Log.i(TAG, "probe $addr: timeout"); probed += addr; dropProbe() } }, 12_000)
    }

    private fun dropProbe() {
        probing = null
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        h.postDelayed({ probeNext() }, 500)
    }

    /** Every device heard in scans (debug: GET /api/ble). */
    val heard = java.util.concurrent.ConcurrentHashMap<String, org.json.JSONObject>()

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName
            if (name != null) seen[result.device.address] = name
            val rec = result.scanRecord
            val makers = rec?.manufacturerSpecificData?.let { m -> (0 until m.size()).map { "%04x".format(m.keyAt(it)) } } ?: emptyList()
            heard[result.device.address] = org.json.JSONObject().put("address", result.device.address).put("name", name ?: org.json.JSONObject.NULL)
                .put("rssi", result.rssi).put("services", org.json.JSONArray(rec?.serviceUuids?.map { it.uuid.toString().substring(4, 8) } ?: emptyList<String>()))
                .put("makers", org.json.JSONArray(makers)).put("connectable", result.isConnectable)
            // like the bridge: a BYD-* name or the heater's GATT service FFE0 in the advertisement
            val hasService = result.scanRecord?.serviceUuids?.any { it.uuid == HeaterProtocol.SERVICE } == true
            val byName = name != null && name.uppercase().startsWith(namePrefix.uppercase())
            if (!byName && !hasService) return
            h.post {
                if (!scanning) return@post
                stopScan()
                deviceName = name ?: result.device.address
                connect(result.device)
            }
        }
    }

    private fun connect(dev: BluetoothDevice) {
        lastError = "connecting…"
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun reconnect(why: String) {
        Log.i(TAG, "heater reconnect: $why")
        connected = false
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null; chr = null; writing = false; writes.clear()
        h.postDelayed({ connectOrScan() }, 5000)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            h.post {
                if (g != gatt) { runCatching { g.close() }; return@post } // late event from a closed connection
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    address = g.device.address
                    deviceName = g.device.name ?: deviceName
                    g.discoverServices()
                } else if (probing != null && !connected) {
                    Log.i(TAG, "probe $probing: disconnected (status $status)")
                    probed += probing!!
                    dropProbe()
                } else {
                    // a stored address that never connects may be stale: rescan by name next time
                    if (!connected) address = null
                    lastError = "disconnected"
                    reconnect("state $newState status $status")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            h.post {
                if (g != gatt) return@post
                Log.i(TAG, "gatt ${g.device.address}: services " + g.services.joinToString(" ") { it.uuid.toString().substring(4, 8) })
                val c = g.getService(HeaterProtocol.SERVICE)?.getCharacteristic(HeaterProtocol.CHAR)
                if (c == null) {
                    if (probing != null) { probed += probing!!; dropProbe(); return@post }
                    lastError = "not a heater (no FFE1)"; address = null; reconnect("no FFE1"); return@post
                }
                probing = null
                chr = c
                g.setCharacteristicNotification(c, true)
                val d = c.getDescriptor(HeaterProtocol.CCCD)
                if (d != null) {
                    @Suppress("DEPRECATION")
                    d.value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(d)
                } else ready()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            h.post { ready() }
        }

        @Deprecated("Deprecated in API 33; the Z1 runs API 30")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = c.value ?: return
            HeaterProtocol.parse(v)?.let { status = it; lastUpdate = System.currentTimeMillis(); lastError = null }
        }
    }

    private fun ready() {
        if (connected) return
        connected = true
        lastError = null
        lastUpdate = System.currentTimeMillis()
        Log.i(TAG, "heater connected: $deviceName $address")
        h.removeCallbacks(pollTask)
        h.post(pollTask)
    }

    companion object { const val TAG = "Camper" }
}
