package io.github.trebronwayne.droplink.core

import android.content.Context

data class HotspotDetails(val ssid: String, val password: String, val open: Boolean)

/**
 * The hotspot name and password, entered once by the owner. Android does not let apps read the hotspot password,
 * so DropLink keeps its own copy (app-private storage, never sent over the network) to build the Wi-Fi QR code.
 */
object HotspotInfo {
    private const val PREFS = "droplink_hotspot"

    fun load(context: Context): HotspotDetails? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ssid = p.getString("ssid", null) ?: return null
        return HotspotDetails(ssid, p.getString("password", "") ?: "", p.getBoolean("open", false))
    }

    fun save(context: Context, d: HotspotDetails) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("ssid", d.ssid).putString("password", d.password).putBoolean("open", d.open).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun esc(s: String): String {
        val e = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
        // A value made only of hex digits would be read as raw hex, so it must be quoted.
        return if (e.isNotEmpty() && e.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) "\"$e\"" else e
    }

    /** The standard Wi-Fi QR payload understood by the Android and iPhone cameras. */
    fun payload(d: HotspotDetails): String =
        if (d.open) "WIFI:T:nopass;S:${esc(d.ssid)};;" else "WIFI:T:WPA;S:${esc(d.ssid)};P:${esc(d.password)};;"
}
