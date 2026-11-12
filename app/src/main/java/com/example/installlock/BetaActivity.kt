package com.example.installlock

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

/** Deliberately separate from the stable dashboard. Nothing turns on simply by opening it. */
class BetaActivity : AppCompatActivity() {
    private lateinit var column: LinearLayout
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var importName = "Imported category"
    override fun onCreate(savedInstanceState: Bundle?) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this); super.onCreate(savedInstanceState)
        try { home() } catch (_: RuntimeException) { screen("Beta features unavailable"); label("The beta database could not be opened. Stable Gouge protections continue."); button("Back") { finish() } }
    }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
    private fun screen(title: String) {
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 40) }
        BetaScreen.show(this, ScrollView(this).apply { addView(column) })
        label(title, 24f)
    }
    private fun label(text: String, size: Float = 15f) { column.addView(UiStyle.label(this, text, size)) }
    private fun button(text: String, action: () -> Unit) { column.addView(UiStyle.button(this, text) { guarded(action) }) }
    private fun guarded(action: () -> Unit) { try { action() } catch (e: Exception) { Toast.makeText(this, e.message ?: "Could not apply beta change", Toast.LENGTH_LONG).show() } }
    private fun field(title: String, value: String = "", numeric: Boolean = false, lines: Int = 1): EditText {
        label(title)
        return EditText(this).apply {
            setText(value); inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            minLines = lines; column.addView(this); UiStyle.styleInputs(this)
        }
    }
    private fun check(title: String, value: Boolean) = CheckBox(this).apply { text = title; isChecked = value; column.addView(this) }
    private fun change(next: BetaConfig, title: String, done: () -> Unit = { home() }) {
        val old = BetaStore.config(this)
        fun apply() { guarded { val immediate = BetaActions.request(this, next, title); if (immediate) done() else Toast.makeText(this, "Confirm the saved beta change in Gouge", Toast.LENGTH_LONG).show() } }
        if (!BetaActions.tightening(old, next) && old.on("sentence")) {
            val input = EditText(this)
            AlertDialog.Builder(this).setTitle("Retype before requesting access").setMessage(old.sentence).setView(input)
                .setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ ->
                    if (input.text.toString() == old.sentence) apply() else Toast.makeText(this, "The sentence did not match", Toast.LENGTH_LONG).show()
                }.show()
        } else apply()
    }
    private fun home() {
        val c = BetaStore.config(this)
        screen("Beta features")
        label("Experimental and off by default. Existing Gouge locks always take priority. Enable only the features you want to try. Beta usage stays on this phone for 14 days.")
        button(if (c.enabled) "Disable beta features (delayed)" else "Enable beta features") {
            if (!Policy.isOwner(this)) { Toast.makeText(this, "This phone can act as a companion. The protected phone must be a device owner.", Toast.LENGTH_LONG).show(); return@button }
            if (!c.enabled) {
                buttonBusy("Preparing beta credential…") {
                    TaskPasswords.setupBeta(this)?.let { throw IllegalStateException(it) }
                    runOnUiThread { change(c.copy(enabled = true), "Enable beta features") }
                }
            } else change(c.copy(enabled = false), "Disable beta features")
        }
        label(if (c.enabled) "Beta: ON" else "Beta: OFF")
        label(if (BrowserKeywordService.isReady(this)) "Usage/feeds monitor connected" else "Usage and feed features require Gouge's browser accessibility service. Counters only measure observed foreground use.")
        button("Profiles, schedules & limits") { profiles() }
        button("Feed, browser & display options") { options() }
        button("Custom UI rules & node picker") { uiRules() }
        button("DNS app scope & category lists") { dns() }
        button("Local accountability companion") { startActivity(Intent(this, BetaCompanionActivity::class.java)) }
        button("Usage & progress") { progress() }
        button("Back to Gouge") { finish() }
    }
    private fun buttonBusy(message: String, work: () -> Unit) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        io.execute { try { work() } catch (e: Exception) { runOnUiThread { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() } } }
    }
    private fun profiles() {
        val c = BetaStore.config(this)
        screen("Beta profiles")
        label("Each profile can combine apps, website paths and weekly hours. Zero limits mean unlimited. Schedules use the profile's saved time zone; overnight windows belong to their starting day.")
        c.profiles.forEach { p -> button("${p.name} · ${if (p.enabled) "on" else "off"}${if (p.committed(System.currentTimeMillis())) " · committed" else ""}") { profile(p) } }
        button("New profile") { profile(BetaProfile(UUID.randomUUID().toString(), "New profile")) }
        button("Back") { home() }
    }
    private fun profile(p: BetaProfile) {
        val c = BetaStore.config(this)
        screen(p.name)
        val name = field("Profile name", p.name)
        val enabled = check("Enable this profile", p.enabled)
        val apps = field("App package names, one per line", p.apps.joinToString("\n"), lines = 2)
        button("Choose installed apps") {
            val installed = packageManager.getInstalledApplications(0).filter { it.packageName !in BetaSafety.essentials(this) && packageManager.getLaunchIntentForPackage(it.packageName) != null }.sortedBy { it.loadLabel(packageManager).toString().lowercase() }
            val selected = apps.text.toString().lines().map { it.trim() }.toMutableSet()
            AlertDialog.Builder(this).setTitle("Apps in ${p.name}").setMultiChoiceItems(installed.map { it.loadLabel(packageManager).toString() }.toTypedArray(), installed.map { it.packageName in selected }.toBooleanArray()) { _, i, yes ->
                if (yes) selected.add(installed[i].packageName) else selected.remove(installed[i].packageName)
            }.setPositiveButton("Use selection") { _, _ -> apps.setText(selected.filter { it.isNotBlank() }.joinToString("\n")) }.setNegativeButton("Cancel", null).show()
        }
        val sites = field("Website/path deny rules (example: youtube.com/shorts)", p.sites.joinToString("\n"), lines = 2)
        val windows = field("Weekly windows: Mon,Tue 21:00-06:00 (one per line; empty = always)", p.windows.joinToString("\n") { w ->
            days.filterIndexed { i, _ -> w.days and (1 shl i) != 0 }.joinToString(",") + " ${time(w.start)}-${time(w.end)}"
        }, lines = 2)
        label("Saved time zone: ${p.zone}")
        val block = check("Block selected apps throughout scheduled hours", p.block)
        val daily = field("Daily minutes per app (0 = unlimited)", "${p.dailyMinutes}", true)
        val group = field("Shared daily minutes across all selected apps", "${p.groupMinutes}", true)
        val session = field("Continuous group session cap, minutes", "${p.sessionMinutes}", true)
        val pause = field("Mandatory break, minutes", "${p.breakMinutes}", true)
        val launch = field("Daily openings per app", "${p.launches}", true)
        val shortTime = field("Daily short-video minutes across group", "${p.shortMinutes}", true)
        val shortCount = field("Daily short-video count across group", "${p.shortCount}", true)
        val stretch = field("Sip & stretch after this many usage minutes (0 = off)", "${p.stretchMinutes}", true)
        val gray = check("Grayscale during this profile's scheduled app use", p.gray)
        label("Grayscale needs Android secure-settings permission. Home and other apps keep their original display settings. Feed detection depends on app versions and can miss videos.")
        button("Save profile") {
            require(apps.text.toString().lines().none { it.trim() in BetaSafety.essentials(this) }) { "Home, Settings, keyboard and the default phone app cannot be beta targets" }
            val n = p.copy(name = name.text.toString().trim(), enabled = enabled.isChecked,
                apps = apps.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                sites = sites.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                windows = parseWindows(windows.text.toString()), block = block.isChecked, dailyMinutes = daily.number(), groupMinutes = group.number(),
                sessionMinutes = session.number(), breakMinutes = pause.number(), launches = launch.number(), shortMinutes = shortTime.number(), shortCount = shortCount.number(),
                gray = gray.isChecked, stretchMinutes = stretch.number())
            change(c.copy(profiles = c.profiles.filterNot { it.id == p.id } + n), "Change beta profile: ${p.name}") { profiles() }
        }
        if (c.profiles.any { it.id == p.id }) {
            button("Pomodoro session…") { pomodoro(p) }
            button("Commit this profile…") { commitment(p) }
            button("Commit until a date…") {
                require(c.enabled && p.enabled) { "Enable beta and the profile first" }
                val today = java.time.Instant.now().atZone(java.time.ZoneId.of(p.zone))
                android.app.DatePickerDialog(this, { _, year, month, day ->
                    android.app.TimePickerDialog(this, { _, hour, minute -> guarded {
                        val until = java.time.LocalDateTime.of(year, month + 1, day, hour, minute).atZone(java.time.ZoneId.of(p.zone)).toInstant().toEpochMilli()
                        require(until in (System.currentTimeMillis() + 60000)..(System.currentTimeMillis() + 365 * 86400_000L)) { "Choose a future date within one year" }
                        AlertDialog.Builder(this).setTitle("Commit until selected date?").setMessage("Rules cannot be relaxed before this date (${p.zone}). You can still add targets or extend the commitment.")
                            .setNegativeButton("Cancel",null).setPositiveButton("Commit") { _, _ -> guarded { change(c.copy(profiles=c.profiles.map { if (it.id==p.id) it.copy(commitment=maxOf(it.commitment,until)) else it }),"Commit beta profile until date") } }.show()
                    } }, today.hour, today.minute, true).show()
                }, today.year, today.monthValue-1, today.dayOfMonth).show()
            }
            button("Request bonus minutes…") { numberDialog("Bonus minutes (delayed)", 1..120) { n ->
                val today = p.day(System.currentTimeMillis())
                val np = p.copy(bonusDay = today, bonusMinutes = (if (p.bonusDay == today) p.bonusMinutes else 0) + n)
                change(c.copy(profiles = c.profiles.map { if (it.id == p.id) np else it }), "Add $n beta bonus minutes to ${p.name}")
            } }
            button("Delete profile (delayed when active)") { change(c.copy(profiles = c.profiles.filterNot { it.id == p.id }), "Delete beta profile: ${p.name}") }
        }
        button("Cancel") { profiles() }
    }
    private fun EditText.number() = text.toString().toIntOrNull() ?: throw IllegalArgumentException("Enter a whole number for every limit")
    private fun numberDialog(title: String, range: IntRange, result: (Int) -> Unit) {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ -> guarded { val n = input.number(); require(n in range) { "Choose ${range.first}–${range.last}" }; result(n) } }.show()
    }
    private fun commitment(p: BetaProfile) {
        AlertDialog.Builder(this).setTitle("Commit ${p.name}?").setMessage("During a commitment you can add targets or extend the deadline. Disabling beta, removing rules, bonus time and companion unlocks cannot end it early. Save and enable your profile before committing.")
            .setNegativeButton("Cancel", null).setPositiveButton("Choose days") { _, _ -> numberDialog("Commit for days", 1..365) { n ->
                val c = BetaStore.config(this)
                require(c.enabled && p.enabled) { "Enable beta and save this profile first" }
                change(c.copy(profiles = c.profiles.map { if (it.id == p.id) it.copy(commitment = maxOf(it.commitment, System.currentTimeMillis() + n * 86400_000L)) else it }), "Extend beta commitment")
            } }.setNeutralButton("Permanent…") { _, _ ->
                AlertDialog.Builder(this).setTitle("Permanent commitment").setMessage("This has no cancellation date. Existing rules will remain add-only. This is not required to use beta features.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Commit permanently") { _, _ -> guarded {
                        val c = BetaStore.config(this); require(c.enabled && p.enabled) { "Enable beta and the profile first" }
                        change(c.copy(profiles = c.profiles.map { if (it.id == p.id) it.copy(permanent = true) else it }), "Commit beta profile permanently")
                    } }.show()
            }.show()
    }
    private fun pomodoro(p: BetaProfile) {
        screen("Pomodoro · ${p.name}")
        label("Focus phases block the profile's apps and sites; breaks lift only the Pomodoro lock. Other scheduled or stable locks still apply. Once started, this session cannot be shortened.")
        val focus = field("Focus minutes (1–240)", "${p.pomodoroMinutes}", true)
        val pause = field("Break minutes (1–120)", "${p.pomodoroBreak}", true)
        val cycles = field("Cycles (1–8)", "4", true)
        button("Start session") {
            require(!p.committed(System.currentTimeMillis())) { "A committed profile cannot start a different session" }
            require(BetaStore.enabled(this)) { "Enable beta first" }
            val n = cycles.number(); require(n in 1..8)
            val start = System.currentTimeMillis()
            val np = p.copy(enabled = true, focusStart = start, focusEnd = start + n * (focus.number() + pause.number()) * 60_000L,
                pomodoroMinutes = focus.number(), pomodoroBreak = pause.number())
            val c = BetaStore.config(this)
            // Starting a non-cancellable session strengthens the profile; no temporary access is granted.
            require(BetaStore.save(this, c.copy(profiles = c.profiles.map { if (it.id == p.id) np else it })))
            BetaActions.changed(this); home()
        }
        button("Cancel") { profiles() }
    }
    private fun options() {
        val c = BetaStore.config(this); screen("Beta feed & browser options")
        label("Selectors are best-effort overlays, not changes to another app's code. Apps may briefly draw content before accessibility reports it. Restricted Mode below applies to YouTube in Chrome, not the YouTube app.")
        val labels = linkedMapOf("shortBlock" to "Block native Shorts & Reels", "hideFeeds" to "Hide native Home/feed navigation", "hideYoutubeHome" to "Hide YouTube Home results (keep search)", "hideYoutubeSuggestions" to "Hide YouTube suggestions/comments below video", "hideInstagramHome" to "Hide Instagram Home feed posts", "hideInstagramExplore" to "Hide Instagram Explore grid", "hideShortTabs" to "Hide native Shorts/Reels tabs", "counter" to "Show live short-video counter", "safeSearch" to "Chrome Google SafeSearch", "youtubeRestrict" to "Chrome YouTube strict Restricted Mode", "proxies" to "Block translation/archive proxies and data/blob/file pages", "sentence" to "Retype sentence before beta relaxation", "grayscale" to "Allow profile grayscale", "worker" to "Serialize policy updates on background worker", "recovery" to "Independent protection recovery steps", "companion" to "Allow local accountability companion server")
        val checks = labels.mapValues { (key, title) -> check(title, key in c.flags) }
        val sentence = field("Retype sentence", c.sentence)
        button("Save options") { change(c.copy(flags = (c.flags - labels.keys) + checks.filterValues { it.isChecked }.keys, sentence = sentence.text.toString()), "Change beta feature options") }
        button("Back") { home() }
    }
    private fun uiRules() {
        val c = BetaStore.config(this); screen("Custom UI rules · beta")
        label("Only resource-ID selectors are supported. No scripts run. A hidden control is covered with an opaque touch-blocking overlay. Password fields are excluded. Use the node picker to capture resource IDs from the current foreground app.")
        c.ui.forEachIndexed { i, r -> button("Remove ${r.resource} (${if (r.hide) "hide" else "block"})") { change(c.copy(ui = c.ui.filterIndexed { j, _ -> i != j }), "Remove beta UI rule") } }
        val pkg = field("App package name")
        val resource = field("Full resource ID (package:id/name)")
        val text = field("Optional exact text match (leave empty for ID only)")
        val hide = check("Hide this element (otherwise block the screen)", true)
        button("Add rule") { change(c.copy(ui = c.ui + BetaUiRule(pkg.text.toString().trim(), resource.text.toString().trim(), text.text.toString(), hide.isChecked)), "Add beta UI rule") { uiRules() } }
        button("Arm node picker for next app") { BetaMonitor.pickNext = true; Toast.makeText(this, "Open the target app within 30 seconds. Return here to see captured IDs.", Toast.LENGTH_LONG).show(); BetaMonitor.pickUntil = android.os.SystemClock.elapsedRealtime() + 30_000 }
        button("Show captured IDs") {
            val ids = BetaMonitor.pickedIds
            AlertDialog.Builder(this).setTitle("Visible resource IDs").setItems(ids.toTypedArray()) { _, i ->
                resource.setText(ids[i]); pkg.setText(ids[i].substringBefore(":id/"))
            }.setNegativeButton("Close", null).show()
        }
        button("Back") { home() }
    }
    private fun dns() {
        val c = BetaStore.config(this); screen("Beta DNS scope & categories")
        label("Google stays in the VPN scope. Extra apps use the same DNS-only filter. Encrypted/private DNS or cached connections can bypass DNS category filtering; this is not a full traffic firewall. Importing or updating a list never downloads remote content automatically.")
        val scope = check("Use extra-app VPN scope", "dnsScope" in c.flags)
        val all = check("Filter DNS for all apps (except Gouge itself)", "dnsAll" in c.flags)
        val apps = field("Extra app package names (one per line)", c.dnsApps.joinToString("\n"), lines = 3)
        val lists = check("Enable imported category lists", "dnsLists" in c.flags)
        button("Save DNS options") {
            val targets = apps.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            require(targets.size <= 200 && targets.all { Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(it) }) { "Use installed Android package names" }
            targets.forEach { packageManager.getApplicationInfo(it, 0) }
            change(c.copy(dnsApps = targets, flags = (c.flags - setOf("dnsScope", "dnsLists", "dnsAll")) + (if (scope.isChecked) setOf("dnsScope") else emptySet()) + (if (all.isChecked) setOf("dnsAll") else emptySet()) + (if (lists.isChecked) setOf("dnsLists") else emptySet())), "Change beta DNS scope and categories")
        }
        c.lists.forEach { (name, hosts) -> label("$name · ${hosts.size} domains"); button("Remove category: $name") { change(c.copy(lists = c.lists - name), "Remove DNS category $name") } }
        val name = field("Category name to import/update", importName)
        button("Import/update hosts or domain text file") {
            importName = name.text.toString().trim(); require(importName.length in 1..60)
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("text/*").addCategory(Intent.CATEGORY_OPENABLE), 411)
        }
        button("Back") { home() }
    }
    @Deprecated("Legacy document result for minSdk 30")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 411 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        buttonBusy("Reading category…") {
            val raw = requireNotNull(contentResolver.openInputStream(uri)).use { input ->
                val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                while (true) { val n=input.read(buffer); if (n<0) break; require(out.size()+n <= 4_000_000) { "Choose a list smaller than 4 MB" }; out.write(buffer,0,n) }
                out.toByteArray()
            }
            val hosts = BetaDnsLists.parse(String(raw, Charsets.UTF_8))
            require(hosts.isNotEmpty()) { "No valid domains found" }
            val c = BetaStore.config(this)
            runOnUiThread { guarded { change(c.copy(lists = c.lists + (importName to hosts)), "Update DNS category $importName") } }
        }
    }
    private fun progress() {
        screen("Beta usage & progress")
        label("Observed foreground time only. Short counts estimate scroll bursts, not video playback completion. Per-profile group totals may overlap; they should not be added together.")
        val now = System.currentTimeMillis()
        BetaStore.config(this).profiles.forEach { p -> val u = BetaStore.usage(this, p, "*", now)
            label("${p.name}: ${u.used / 60000} min · ${u.shorts / 60000} short-video min · ${u.count} shorts · ${u.launches} openings", 18f)
            if (u.pauseUntil > now) label("Break remaining: ${(u.pauseUntil - now + 59999) / 60000} min")
        }
        label("Add the Beta usage widget from your home screen's widget picker.")
        button("Refresh") { progress() }; button("Back") { home() }
    }
    companion object {
        private val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        private fun time(n: Int) = "%02d:%02d".format(n / 60, n % 60)
        internal fun parseWindows(text: String): List<BetaWindow> = text.lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.trim().split(Regex("\\s+")); require(parts.size == 2) { "Use Mon,Tue 21:00-06:00" }
            val mask = parts[0].split(',').map { d -> days.indexOfFirst { it.equals(d, true) }.also { require(it >= 0) { "Use Mon,Tue,Wed,Thu,Fri,Sat,Sun" } } }.fold(0) { m, i -> m or (1 shl i) }
            val times = parts[1].split('-'); require(times.size == 2)
            fun minute(s: String): Int { val a = s.split(':'); require(a.size == 2); val h = a[0].toInt(); val m = a[1].toInt(); require(h in 0..24 && m in 0..59 && (h < 24 || m == 0)); return h * 60 + m }
            BetaWindow(mask, minute(times[0]), minute(times[1]))
        }
    }
}

internal object BetaDnsLists {
    fun parse(raw: String): Set<String> = raw.lineSequence().flatMap { line ->
        val parts = line.substringBefore('#').trim().split(Regex("\\s+"))
        (if (parts.firstOrNull() in setOf("0.0.0.0", "127.0.0.1", "::", "::1")) parts.drop(1) else parts.take(1)).asSequence()
    }.map { it.lowercase().trimEnd('.') }.filter { it.length <= 253 &&
        Regex("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}").matches(it) }
        .take(50001).toSet().also { require(it.size <= 50000) { "Use at most 50,000 domains per configuration" } }
}
