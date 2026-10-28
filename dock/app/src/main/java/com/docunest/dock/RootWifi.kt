package com.docunest.dock

import android.net.wifi.WifiConfiguration
import android.os.IBinder

/**
 * Run as root (`app_process`, see [WifiDoctor.rejoin]) to do what only the
 * Settings app may: re-enable and connect a saved network. Android 10
 * blocks auto-join for a network that once had no internet
 * (NETWORK_SELECTION_..._DISABLED_NO_INTERNET_*) — the iPhone hotspot after
 * a tunnel — and never rejoins it by itself; a "user" connect clears that.
 *
 *   status <ssid-hex>   prints "<netId> <selection status> <disable reason>"
 *   enable <ssid-hex>   enable + connect; prints "ok <netId>" or the problem
 *
 * The SSID is hex so names like "Lukas’s iPhone" survive the shell.
 */
object RootWifi {
    private const val PKG = "root"

    @JvmStatic
    fun main(args: Array<String>) {
        val out = runCatching { run(args) }.getOrElse { "error ${it.javaClass.simpleName}: ${it.message}" }
        println(out)
    }

    private fun run(args: Array<String>): String {
        val op = args.getOrNull(0) ?: return "error no command"
        val ssid = args.getOrNull(1)?.let { String(it.chunked(2).map { b -> b.toInt(16).toByte() }.toByteArray(), Charsets.UTF_8) }
            ?: return "error no ssid"
        val wifi = service()
        val config = configured(wifi).firstOrNull { it.SSID == "\"$ssid\"" } ?: return "missing not saved: $ssid"
        return when (op) {
            "status" -> "${config.networkId} ${selection(config)}"
            "enable" -> {
                val enable = wifi.javaClass.methods.first { it.name == "enableNetwork" }
                val ok = enable.invoke(wifi, config.networkId, true, PKG) as Boolean
                runCatching { wifi.javaClass.methods.first { it.name == "reconnect" }.invoke(wifi, PKG) }
                if (ok) "ok ${config.networkId}" else "refused ${config.networkId}"
            }
            else -> "error unknown command $op"
        }
    }

    private fun service(): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "wifi") as IBinder
        return Class.forName("android.net.wifi.IWifiManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    /** getConfiguredNetworks' parameters differ by Android version (pkg / featureId / callerNetworksOnly). */
    @Suppress("UNCHECKED_CAST")
    private fun configured(wifi: Any): List<WifiConfiguration> {
        val m = wifi.javaClass.methods.first { it.name == "getConfiguredNetworks" }
        var strings = 0
        val args = m.parameterTypes.map {
            when (it) {
                String::class.java -> if (strings++ == 0) PKG else null
                java.lang.Boolean.TYPE -> false
                else -> null
            }
        }
        val slice = m.invoke(wifi, *args.toTypedArray()) ?: return emptyList()
        return slice.javaClass.getMethod("getList").invoke(slice) as List<WifiConfiguration>
    }

    /** "ENABLED -" or "PERMANENTLY_DISABLED NO_INTERNET_PERMANENT" (hidden API, read by reflection). */
    private fun selection(c: WifiConfiguration): String = runCatching {
        val s = c.javaClass.getMethod("getNetworkSelectionStatus").invoke(c)!!
        val status = s.javaClass.getMethod("getNetworkStatusString").invoke(s) as String
        val reason = runCatching {
            val r = s.javaClass.getMethod("getNetworkSelectionDisableReason").invoke(s) as Int
            s.javaClass.getMethod("getNetworkDisableReasonString", Int::class.javaPrimitiveType).invoke(null, r) as String
        }.getOrDefault("-")
        "${status.removePrefix("NETWORK_SELECTION_")} ${reason.removePrefix("NETWORK_SELECTION_DISABLED_")}"
    }.getOrDefault("unknown -")
}
