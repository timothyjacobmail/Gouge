package com.example.installlock

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** The same APK can be installed on a second phone without provisioning Device Owner. */
class BetaCompanionActivity : AppCompatActivity() {
    private lateinit var column: LinearLayout
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("gouge_beta_parent", 0) }
    private fun label(text: String) { column.addView(UiStyle.label(this, text)) }
    private fun button(text: String, action: () -> Unit) { column.addView(UiStyle.button(this, text) { try { action() } catch (e: Exception) { message(e.message) } }) }
    private fun field(title: String, value: String = "") = EditText(this).apply { hint = title; setText(value); column.addView(this); UiStyle.styleInputs(this) }
    private fun message(text: String?) { Toast.makeText(this, text ?: "Companion request failed", Toast.LENGTH_LONG).show() }
    override fun onCreate(state: Bundle?) { com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this); super.onCreate(state); render() }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
    private fun render() {
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,40) }
        BetaScreen.show(this, ScrollView(this).apply { addView(column) })
        label("Local accountability companion · beta")
        label("Both phones must be on the same trusted LAN. Verify the complete fingerprint on the protected phone in person before pairing. Remote unlock affects beta profiles only; stable Gouge locks and committed profiles cannot be overridden.")
        if (Policy.isOwner(this)) {
            label(BetaLanService.status)
            label("Protected phone address:\n${BetaLanService.addresses()}")
            label("SHA-256 certificate fingerprint:\n${BetaLanService.fingerprint.ifEmpty { "Start listener to generate" }}")
            if (SystemClock.elapsedRealtime() < BetaLanService.pairingUntil) label("Pairing code: ${BetaLanService.pairingCode}\nExpires in ${(BetaLanService.pairingUntil - SystemClock.elapsedRealtime()) / 1000} seconds. One use, five attempts.")
            button("Start local listener") { require(BetaStore.config(this).on("companion")) { "Enable beta companion access first" }; BetaLanService.start(this); message("Listener starting; tap Refresh") }
            button("Authorize pairing window (delayed task)") { BetaActions.companionRequest(this, true) }
            button("Revoke all paired companions (delayed task)") { BetaActions.companionRequest(this, false) }
            button("Stop listener") { stopService(Intent(this, BetaLanService::class.java)); message("Listener stopped") }
            button("Refresh pairing information") { render() }
        }
        label("Use this phone as the companion")
        val host = field("Protected phone LAN address", prefs.getString("host", "").orEmpty())
        val pin = field("Full SHA-256 fingerprint", prefs.getString("pin", "").orEmpty())
        val code = field("One-use pairing code")
        button("Pair with protected phone") {
            val address = host.text.toString().trim(); val fingerprint = pin.text.toString().trim(); val pairing = code.text.toString().trim()
            require(Regex("[0-9]{8}").matches(pairing)) { "Enter the eight-digit pairing code" }
            io.execute {
                try {
                    val response = BetaLanClient.send(address, fingerprint, JSONObject().put("op", "pair").put("code", pairing))
                    val id = response.getString("id"); val secret = response.getString("secret")
                    check(prefs.edit().putString("host", address).putString("pin", fingerprint).putString("id", id).putString("key", BetaKeys.seal("parent:$id", secret)).putLong("sequence", 0).commit())
                    runOnUiThread { code.setText(""); message("Paired. Keep this companion phone secure."); render() }
                } catch (e: Exception) { runOnUiThread { message("Pairing failed. Check address, fingerprint, code and pairing window.") } }
            }
        }
        if (prefs.contains("id")) {
            label("Companion paired with ${prefs.getString("host", "")}")
            button("Read beta profiles / send command") { command(JSONObject().put("op", "status")) { response ->
                val a = response.getJSONArray("profiles")
                if (a.length() == 0) { message("No beta profiles on protected phone"); return@command }
                AlertDialog.Builder(this).setTitle("Beta profiles").setItems((0 until a.length()).map { a.getJSONObject(it).let { p -> "${p.getString("name")} · ${p.getLong("minutes")} min${if (p.getBoolean("committed")) " · committed" else ""}" } }.toTypedArray()) { _, i ->
                    val p = a.getJSONObject(i)
                    AlertDialog.Builder(this).setTitle(p.getString("name")).setItems(arrayOf("Lock beta profile", "Temporarily unlock beta profile")) { _, n ->
                        val input = EditText(this).apply { hint = "Minutes (1–120)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
                        AlertDialog.Builder(this).setTitle(if (n == 0) "Remote beta lock" else "Remote beta unlock").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Send") { _, _ ->
                            val minutes = input.text.toString().toIntOrNull()
                            if (minutes == null || minutes !in 1..120) message("Choose 1–120 minutes")
                            else command(JSONObject().put("op", if (n == 0) "lock" else "unlock").put("profile", p.getString("id")).put("minutes", minutes)) { message("Beta command applied. Stable protections still take priority.") }
                        }.show()
                    }.setNegativeButton("Cancel", null).show()
                }.setNegativeButton("Close", null).show()
            } }
            button("Forget companion credentials on this phone") { prefs.edit().clear().commit(); render() }
        }
        button("Back") { finish() }
    }
    private fun command(body: JSONObject, done: (JSONObject) -> Unit) {
        io.execute {
            try {
                val id = prefs.getString("id", null) ?: error("Pair first")
                val secret = BetaKeys.open("parent:$id", prefs.getString("key", null) ?: error("Pair first"))
                val sequence = Math.addExact(prefs.getLong("sequence", 0), 1)
                check(prefs.edit().putLong("sequence", sequence).commit())
                val time = System.currentTimeMillis(); val text = body.toString()
                val request = JSONObject().put("id", id).put("sequence", sequence).put("time", time).put("body", text)
                    .put("mac", Base64.encodeToString(BetaLanProtocol.signature(Base64.decode(secret, Base64.NO_WRAP), id, sequence, time, text), Base64.NO_WRAP))
                val response = BetaLanClient.send(prefs.getString("host", "").orEmpty(), prefs.getString("pin", "").orEmpty(), request)
                runOnUiThread { done(response) }
            } catch (_: Exception) { runOnUiThread { message("Request rejected or phone unreachable. Check listener, pairing, clock and profile commitment.") } }
        }
    }
}
