package com.example.installlock

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Persisted cross-restart request/transfer ceiling shared by both automatic category consumers. */
internal object PlayLookupNetwork {
    data class Reservation(val window: Long, val limit: Int)
    private fun prefs(c: Context)=c.getSharedPreferences("play_lookup_network",Context.MODE_PRIVATE)
    private fun saved(c: Context)=prefs(c).let { PlayLookupAllowance(it.getLong("window",0),it.getInt("requests",0),it.getLong("bytes",0)) }
    fun connected(c: Context, background: Boolean): Boolean = try {
        val cm=c.getSystemService(ConnectivityManager::class.java)
        val cap=cm.getNetworkCapabilities(cm.activeNetwork)
        cap?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            (!background || cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    } catch (_: RuntimeException) { false }
    @Synchronized fun available(c: Context, background: Boolean) = saved(c).permits(System.currentTimeMillis(),background)
    @Synchronized fun reserve(c: Context, background: Boolean): Reservation? {
        if (!connected(c,background)) return null
        val now=System.currentTimeMillis(); val s=saved(c).current(now)
        if (!s.permits(now,background)) return null
        val reserved=minOf(PlayListingReader.MAX_WIRE_BYTES.toLong(),PlayLookupAllowance.MAX_BYTES-s.bytes).toInt()
        if (!prefs(c).edit().putLong("window",s.window).putInt("requests",s.requests+1).putLong("bytes",s.bytes+reserved)
                .putLong("totalRequests",prefs(c).getLong("totalRequests",0)+1).commit()) return null
        return Reservation(s.window,reserved)
    }
    @Synchronized fun received(c: Context, bytes: Long, reservation: Reservation) {
        val s=saved(c)
        val charged=if (s.window == reservation.window) (s.bytes-reservation.limit+bytes).coerceAtLeast(0) else s.bytes
        prefs(c).edit().putLong("bytes",charged).putLong("totalBytes",prefs(c).getLong("totalBytes",0)+bytes).commit()
    }
}
