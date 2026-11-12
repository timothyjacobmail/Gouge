package com.example.installlock

import android.content.Context
import android.os.Bundle
import org.json.JSONArray

internal object BetaChrome {
    /** Stable ChromeRestrictions builds the base lists on every call; beta only adds denies. */
    fun update(c: Context, current: Bundle, stable: Bundle): Bundle {
        val next = Bundle(stable)
        val enabled = BetaStore.enabled(c)
        val conf = if (enabled) BetaStore.config(c) else BetaConfig()
        val now = System.currentTimeMillis()
        val denies = conf.profiles.filter { it.enabled && (it.scheduled(now) || it.focus(now)) && !(it.overrideUntil > now && !it.committed(now)) }.flatMap { it.sites } +
            if (conf.on("proxies")) BetaPaths.proxies + setOf("data:*", "blob:*", "file:*" ) else emptySet()
        if (denies.isNotEmpty()) {
            val base = try { JSONArray(next.getString("URLBlocklist", "[]")) } catch (_: Exception) { JSONArray() }
            val all = (0 until base.length()).map { base.getString(it) } + denies
            next.putString("URLBlocklist", JSONArray(all.distinct().sorted()).toString())
        }
        val p = c.getSharedPreferences("gouge_beta_chrome", 0)
        fun policy(key: String, on: Boolean, value: Int, boolean: Boolean) {
            if (on) {
                if (!p.getBoolean("owned.$key", false)) {
                    val edit = p.edit().putBoolean("owned.$key", true).putBoolean("present.$key", current.containsKey(key))
                    if (boolean) edit.putBoolean("old.$key", current.getBoolean(key, false)) else edit.putInt("old.$key", current.getInt(key, 0))
                    if (!edit.commit()) return
                }
                if (boolean) next.putBoolean(key, true) else next.putInt(key, value)
            } else if (p.getBoolean("owned.$key", false)) {
                if (!p.getBoolean("present.$key", false)) next.remove(key)
                else if (boolean) next.putBoolean(key, p.getBoolean("old.$key", false)) else next.putInt(key, p.getInt("old.$key", 0))
                // Keep ownership until Android has actually accepted/read back the restored value.
            }
        }
        policy("ForceGoogleSafeSearch", conf.on("safeSearch"), 1, true)
        policy("ForceYouTubeRestrict", conf.on("youtubeRestrict"), 2, false)
        return next
    }
    fun equal(a: Bundle, b: Bundle) = a.keySet() == b.keySet() && a.keySet().all { a.get(it) == b.get(it) }
    fun restored(c: Context) {
        val p = c.getSharedPreferences("gouge_beta_chrome", 0)
        val conf = if (BetaStore.enabled(c)) BetaStore.config(c) else BetaConfig()
        val edit = p.edit()
        mapOf("ForceGoogleSafeSearch" to "safeSearch", "ForceYouTubeRestrict" to "youtubeRestrict").forEach { (key, flag) ->
            if (p.getBoolean("owned.$key",false) && !conf.on(flag)) edit.remove("owned.$key").remove("old.$key").remove("present.$key")
        }
        edit.apply()
    }
}
