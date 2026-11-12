package com.example.installlock

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

internal object BetaJson {
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }.toSet()
    fun encode(c: BetaConfig): String = JSONObject().apply {
        put("enabled", c.enabled); put("flags", JSONArray(c.flags.toList())); put("revision", c.revision)
        put("sentence", c.sentence); put("dnsApps", JSONArray(c.dnsApps.toList()))
        put("lists", JSONObject().apply { c.lists.forEach { (name, hosts) -> put(name, JSONArray(hosts.toList())) } })
        put("ui", JSONArray().apply { c.ui.forEach { put(JSONObject().put("pkg", it.pkg).put("resource", it.resource).put("text", it.text).put("hide", it.hide)) } })
        put("profiles", JSONArray().apply { c.profiles.forEach { p -> put(JSONObject().apply {
            put("id", p.id); put("name", p.name); put("apps", JSONArray(p.apps.toList())); put("sites", JSONArray(p.sites.toList()))
            put("enabled", p.enabled); put("zone", p.zone); put("block", p.block); put("gray", p.gray)
            put("windows", JSONArray().apply { p.windows.forEach { put(JSONArray(listOf(it.days, it.start, it.end))) } })
            put("daily", p.dailyMinutes); put("group", p.groupMinutes); put("session", p.sessionMinutes); put("break", p.breakMinutes)
            put("launches", p.launches); put("shortMinutes", p.shortMinutes); put("shortCount", p.shortCount)
            put("stretch", p.stretchMinutes); put("commitment", p.commitment); put("permanent", p.permanent)
            put("focusStart", p.focusStart); put("focusEnd", p.focusEnd); put("pomodoro", p.pomodoroMinutes); put("pomodoroBreak", p.pomodoroBreak)
            put("bonusDay", p.bonusDay); put("bonus", p.bonusMinutes); put("override", p.overrideUntil)
        }) } })
    }.toString()
    fun decode(raw: String): BetaConfig {
        require(raw.length <= 4_000_000)
        val j = JSONObject(raw)
        val ps = j.getJSONArray("profiles")
        val ui = j.getJSONArray("ui")
        val lists = j.getJSONObject("lists")
        return BetaConfig(j.getBoolean("enabled"), j.getJSONArray("flags").strings(), (0 until ps.length()).map { i ->
            val p = ps.getJSONObject(i); val w = p.getJSONArray("windows")
            BetaProfile(p.getString("id"), p.getString("name"), p.getJSONArray("apps").strings(), p.getJSONArray("sites").strings(),
                p.getBoolean("enabled"), p.getString("zone"), (0 until w.length()).map { k -> w.getJSONArray(k).let { BetaWindow(it.getInt(0), it.getInt(1), it.getInt(2)) } },
                p.getBoolean("block"), p.getInt("daily"), p.getInt("group"), p.getInt("session"), p.getInt("break"), p.getInt("launches"),
                p.getInt("shortMinutes"), p.getInt("shortCount"), p.getBoolean("gray"), p.getInt("stretch"), p.getLong("commitment"), p.getBoolean("permanent"),
                p.getLong("focusStart"), p.getLong("focusEnd"), p.getInt("pomodoro"), p.getInt("pomodoroBreak"), p.getString("bonusDay"), p.getInt("bonus"), p.getLong("override"))
        }, (0 until ui.length()).map { ui.getJSONObject(it).let { r -> BetaUiRule(r.getString("pkg"), r.getString("resource"), r.getString("text"), r.getBoolean("hide")) } },
            j.getJSONArray("dnsApps").strings(), lists.keys().asSequence().associateWith { lists.getJSONArray(it).strings() }, j.getString("sentence"), j.getLong("revision"))
    }
}

/** Separate transactional database. No schema or value in the stable store is migrated. */
internal class BetaDatabase(c: Context, name: String = "gouge_beta.db") : SQLiteOpenHelper(c.applicationContext, name, null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE config (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, body TEXT NOT NULL)")
        db.execSQL("INSERT INTO config VALUES(1,0,?)", arrayOf(BetaJson.encode(BetaConfig())))
        db.execSQL("CREATE TABLE history (revision INTEGER PRIMARY KEY, created INTEGER NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE usage (profile TEXT NOT NULL, day TEXT NOT NULL, pkg TEXT NOT NULL, used INTEGER NOT NULL, shorts INTEGER NOT NULL, count INTEGER NOT NULL, launches INTEGER NOT NULL, session INTEGER NOT NULL, pause INTEGER NOT NULL, last INTEGER NOT NULL, PRIMARY KEY(profile,day,pkg))")
        db.execSQL("CREATE TABLE peer (id TEXT PRIMARY KEY, secret TEXT NOT NULL, sequence INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE draft (id TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, created INTEGER NOT NULL, operation TEXT NOT NULL)")
        db.execSQL("CREATE TABLE managed (pkg TEXT PRIMARY KEY)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported beta database version") }
}

internal object BetaStore {
    @Volatile private var helper: BetaDatabase? = null
    @Volatile private var cached: BetaConfig? = null
    @Volatile private var managedCache: Set<String>? = null
    @Volatile var fault: String? = null
        private set
    private data class UsageKey(val profile: String, val day: String, val pkg: String)
    private data class UsageRow(val usage: BetaUsage, val last: Long)
    private val buffered = linkedMapOf<UsageKey, UsageRow>()
    private var lastFlush = 0L
    private var lastPrune = 0L
    private fun db(c: Context): SQLiteDatabase = (helper ?: synchronized(this) {
        helper ?: BetaDatabase(c).also { helper = it }
    }).writableDatabase
    private fun known(c: Context) = c.getSharedPreferences("gouge_beta_marker", 0).getBoolean("used", false)
    fun enabled(c: Context): Boolean = try { known(c) && config(c).enabled } catch (_: RuntimeException) {
        fault = "Beta storage is unavailable. Stable Gouge protections continue."; false
    }
    @Synchronized fun config(c: Context): BetaConfig {
        cached?.let { return it }
        c.getSharedPreferences("gouge_beta_marker", 0).edit().putBoolean("used", true).commit()
        return db(c).rawQuery("SELECT body FROM config WHERE id=1", null).use { it.moveToFirst(); BetaJson.decode(it.getString(0)) }.also { cached = it }
    }
    /** Used by explicit tightening or an authenticated delayed beta task, never by arbitrary intents. */
    @Synchronized fun save(c: Context, next: BetaConfig): Boolean {
        if (next.profiles.any { it.apps.any { pkg -> pkg in BetaSafety.essentials(c) } } || next.ui.any { it.pkg in BetaSafety.essentials(c) }) return false
        flush(c)
        if (!BetaTransactions.save(db(c), next, System.currentTimeMillis())) return false
        cached = next.copy(revision = next.revision + 1)
        managedCache = null
        return true
    }
    @Synchronized fun usage(c: Context, p: BetaProfile, pkg: String, now: Long = System.currentTimeMillis()): BetaUsage {
        buffered[UsageKey(p.id,p.day(now),pkg)]?.let { return it.usage }
        return db(c).rawQuery("SELECT used,shorts,count,launches,session,pause FROM usage WHERE profile=? AND day=? AND pkg=?", arrayOf(p.id, p.day(now), pkg)).use {
            if (!it.moveToFirst()) BetaUsage() else BetaUsage(it.getLong(0), it.getLong(1), it.getInt(2), it.getInt(3), it.getLong(4), it.getLong(5))
        }
    }
    @Synchronized fun record(c: Context, pkg: String, elapsed: Long, short: Boolean, count: Boolean, launch: Boolean, now: Long) {
        val conf = config(c)
        if (!conf.enabled) return
        val d = db(c)
        var breakStarted = false
            for (p in conf.profiles.filter { it.scheduled(now) && pkg in it.apps } + deviceProfile(pkg)) {
                for (key in listOf(pkg, "*")) {
                    val identity = UsageKey(p.id,p.day(now),key)
                    val old = usage(c, p, key, now)
                    val last = buffered[identity]?.last ?: d.rawQuery("SELECT last FROM usage WHERE profile=? AND day=? AND pkg=?", arrayOf(p.id, p.day(now), key)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                    val next = BetaAccounting.advance(p, old, last, now, elapsed, short, count, launch)
                    buffered[identity] = UsageRow(next,now)
                    if (old.pauseUntil <= now && next.pauseUntil > now) breakStarted = true
                }
            }
        if (launch || breakStarted || android.os.SystemClock.elapsedRealtime() - lastFlush >= 5000) flush(c)
    }
    @Synchronized fun flush(c: Context) {
        if (buffered.isEmpty()) return
        val d = db(c)
        val now = System.currentTimeMillis()
        val prune = now-lastPrune >= 3600_000
        d.beginTransaction()
        try {
            buffered.forEach { (key,row) -> val u=row.usage
                d.execSQL("INSERT OR REPLACE INTO usage VALUES(?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>(key.profile,key.day,key.pkg,u.used,u.shorts,u.count,u.launches,u.session,u.pauseUntil,row.last))
            }
            // Bounded retention; browsing titles, text and URLs are never stored.
            if (prune) d.execSQL("DELETE FROM usage WHERE last < ?", arrayOf(now - 14 * 86400_000L))
            d.setTransactionSuccessful()
        } finally { d.endTransaction() }
        buffered.clear(); lastFlush=android.os.SystemClock.elapsedRealtime(); if (prune) lastPrune=now
    }
    fun deviceProfile(pkg: String = "com.example.placeholder") = BetaProfile("beta-device", "Device", apps = setOf(pkg), enabled = true)
    @Synchronized private fun expire(c: Context) {
        val now=System.currentTimeMillis()
        if (now < lastPrune || now-lastPrune >= 3600_000L) {
            db(c).execSQL("DELETE FROM usage WHERE last < ?",arrayOf(now-14*86400_000L))
            lastPrune=now
        }
    }
    fun managed(c: Context): Set<String> = try {
        if (!known(c)) emptySet() else { expire(c); managedCache ?: db(c).rawQuery("SELECT pkg FROM managed", null).use {
            buildSet { while (it.moveToNext()) add(it.getString(0)) }.also { managedCache = it }
        } }
    } catch (_: RuntimeException) {
        fault = "Beta storage is unavailable. Stable Gouge protections continue."; managedCache.orEmpty()
    }
    fun reasons(c: Context, pkg: String, now: Long): List<String> {
        if (!enabled(c)) return emptyList()
        return try {
        if (!BrowserKeywordService.isReady(c) && config(c).profiles.any { p -> pkg in p.apps && p.scheduled(now) })
            return listOf("Beta usage limits need Gouge's browser accessibility service. Reconnect monitoring to resume access.")
        config(c).profiles.flatMap { p -> BetaRules.reasons(p, pkg, now, usage(c, p, pkg, now), usage(c, p, "*", now)) }
        } catch (_: RuntimeException) { listOf("Beta profile counters could not be read. Open Beta features to check storage.") }
    }
    fun blocks(c: Context, host: String): Boolean {
        if (!enabled(c) || !config(c).on("dnsLists")) return false
        var suffix = host.lowercase().trimEnd('.')
        val lists = config(c).lists.values
        while (suffix.contains('.')) {
            if (lists.any { suffix in it }) return true
            suffix = suffix.substringAfter('.')
        }
        return false
    }
    fun peer(c: Context, id: String): Pair<String, Long>? = db(c).rawQuery("SELECT secret,sequence FROM peer WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
    }
    @Synchronized fun addPeer(c: Context, id: String, secret: String) {
        check(db(c).rawQuery("SELECT COUNT(*) FROM peer", null).use { it.moveToFirst(); it.getInt(0) } < 8) { "Revoke old companions before pairing another" }
        db(c).execSQL("INSERT OR IGNORE INTO peer VALUES(?,?,0)", arrayOf(id, secret))
    }
    @Synchronized fun consume(c: Context, id: String, sequence: Long): Boolean {
        return BetaTransactions.consume(db(c), id, sequence)
    }
    fun clearPeers(c: Context) { db(c).delete("peer", null, null) }
    fun draft(c: Context, next: BetaConfig, title: String, operation: String = "config"): String = java.util.UUID.randomUUID().toString().also {
        require(operation in setOf("config", "pair", "unpair"))
        db(c).execSQL("INSERT INTO draft VALUES(?,?,?,?,?)", arrayOf<Any>(it, title.take(160), BetaJson.encode(next), System.currentTimeMillis(), operation))
    }
    fun draft(c: Context, id: String): BetaDraft? = db(c).rawQuery("SELECT title,body,operation FROM draft WHERE id=? AND created>?", arrayOf(id, (System.currentTimeMillis() - 7 * 86400_000L).toString())).use {
        if (it.moveToFirst()) BetaDraft(it.getString(0), BetaJson.decode(it.getString(1)), it.getString(2)) else null
    }
    fun removeDraft(c: Context, id: String) { db(c).delete("draft", "id=?", arrayOf(id)) }
}

internal data class BetaDraft(val title: String, val config: BetaConfig, val operation: String)

/** The same transactions can be tested against a disposable DB without touching real policy. */
internal object BetaTransactions {
    fun save(d: SQLiteDatabase, next: BetaConfig, now: Long): Boolean {
        d.beginTransaction()
        try {
            val old = d.rawQuery("SELECT body FROM config WHERE id=1", null).use { it.moveToFirst(); BetaJson.decode(it.getString(0)) }
            if (old.revision != next.revision || !old.accepts(next, now)) return false
            d.execSQL("INSERT INTO history VALUES(?,?,?)", arrayOf<Any>(old.revision, now, BetaJson.encode(old)))
            d.execSQL("UPDATE config SET revision=?,body=? WHERE id=1 AND revision=?", arrayOf<Any>(old.revision + 1, BetaJson.encode(next.copy(revision=old.revision+1)), old.revision))
            next.profiles.flatMap { it.apps }.toSet().forEach { d.execSQL("INSERT OR IGNORE INTO managed VALUES(?)", arrayOf(it)) }
            d.execSQL("DELETE FROM history WHERE revision < ?", arrayOf((old.revision - 19).coerceAtLeast(0)))
            d.setTransactionSuccessful()
            return true
        } finally { d.endTransaction() }
    }
    fun consume(d: SQLiteDatabase, id: String, sequence: Long): Boolean {
        if (sequence <= 0) return false
        return d.compileStatement("UPDATE peer SET sequence=? WHERE id=? AND sequence < ?").use { s ->
            s.bindLong(1, sequence); s.bindString(2, id); s.bindLong(3, sequence); s.executeUpdateDelete() == 1
        }
    }
}
