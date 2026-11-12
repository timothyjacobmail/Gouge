package com.example.installlock

import android.content.Context
import android.content.Intent

internal object BetaActions {
    const val CONFIRM = "com.example.installlock.BETA_CONFIRM"
    fun tightening(old: BetaConfig, next: BetaConfig): Boolean {
        if (!old.enabled) return true
        if ("companion" !in old.flags && "companion" in next.flags) return false
        if (!next.enabled || !next.flags.containsAll(old.flags) || !next.ui.containsAll(old.ui) || next.sentence != old.sentence ||
            !next.dnsApps.containsAll(old.dnsApps) || old.lists.any { (k,v) -> next.lists[k]?.containsAll(v) != true }) return false
        return old.profiles.all { p -> next.profiles.find { it.id == p.id }?.let { n ->
            n.apps.containsAll(p.apps) && n.sites.containsAll(p.sites) && n.commitment >= p.commitment && (!p.permanent || n.permanent) &&
                (!p.enabled || n.enabled) && n.copy(name=p.name, apps=p.apps, sites=p.sites, commitment=p.commitment, permanent=p.permanent, enabled=p.enabled) == p
        } == true }
    }
    fun request(c: Context, next: BetaConfig, title: String): Boolean {
        val old = BetaStore.config(c)
        require(old.accepts(next, System.currentTimeMillis())) { "An active commitment or Pomodoro session cannot be relaxed. Add targets or extend the commitment instead." }
        if (tightening(old, next)) {
            check(BetaStore.save(c, next)) { "Settings changed. Reopen this screen." }
            changed(c)
            return true
        }
        val id = BetaStore.draft(c, next, title)
        c.startActivity(Intent(c, MainActivity::class.java).setAction(CONFIRM).putExtra("draft", id))
        return false
    }
    fun execute(c: Context, id: String): Boolean {
        val draft = BetaStore.draft(c, id) ?: return false
        if (!BetaStore.save(c, draft.config)) return false
        when (draft.operation) {
            "pair" -> { if (!BetaStore.config(c).on("companion")) return false; BetaLanService.start(c, pair = true) }
            "unpair" -> BetaStore.clearPeers(c)
        }
        BetaStore.removeDraft(c, id)
        changed(c)
        return true
    }
    fun companionRequest(c: Context, pair: Boolean) {
        val config = BetaStore.config(c)
        check(config.on("companion")) { "Enable beta companion access in Feed, browser & display options first" }
        val id = BetaStore.draft(c, config, if (pair) "Authorize local companion pairing" else "Revoke all local companions", if (pair) "pair" else "unpair")
        c.startActivity(Intent(c, MainActivity::class.java).setAction(CONFIRM).putExtra("draft", id))
    }
    fun changed(c: Context) {
        BetaEnforcement.request(c)
        BetaMonitor.changed()
        DnsFilterService.refreshBetaScope()
        BetaStatsWidget.refresh(c)
        if (!BetaStore.config(c).on("companion")) c.stopService(Intent(c, BetaLanService::class.java))
    }
}
