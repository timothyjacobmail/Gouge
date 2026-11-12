package com.example.installlock

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class BetaRulesTest {
    private val monday = Instant.parse("2026-11-09T12:00:00Z").toEpochMilli()
    private fun at(s: String) = Instant.parse(s).toEpochMilli()
    private val p = BetaProfile("test", "Study", setOf("com.example.media"), enabled = true, zone = "UTC")
    private val pkg = "com.example.media"
    @Test fun defaultBetaAndEveryOptionalFlagAreOff() {
        val c = BetaConfig()
        assertFalse(c.enabled); assertTrue(c.flags.isEmpty()); assertTrue(c.profiles.isEmpty())
        assertFalse(c.on("companion")); assertFalse(c.on("safeSearch"))
        assertTrue(BetaRules.reasons(p.copy(enabled=false), pkg, monday, BetaUsage(), BetaUsage()).isEmpty())
    }
    @Test fun zeroBudgetsAreUnlimited() { assertTrue(BetaRules.reasons(p, pkg, monday, BetaUsage(Long.MAX_VALUE), BetaUsage(Long.MAX_VALUE)).isEmpty()) }
    @Test fun onlySelectedAppsAreAffected() { assertTrue(BetaRules.reasons(p.copy(block=true), "com.other.app", monday, BetaUsage(), BetaUsage()).isEmpty()) }
    @Test fun overnightUsesStartDayAndEndIsExclusive() {
        val w = BetaWindow(1, 21*60, 6*60)
        assertFalse(w.active(at("2026-11-09T20:59:59Z"), "UTC"))
        assertTrue(w.active(at("2026-11-09T21:00:00Z"), "UTC"))
        assertTrue(w.active(at("2026-11-10T05:59:59Z"), "UTC"))
        assertFalse(w.active(at("2026-11-10T06:00:00Z"), "UTC"))
        assertFalse(w.active(at("2026-11-10T21:00:00Z"), "UTC"))
    }
    @Test fun sundayOvernightWrapsIntoMonday() { assertTrue(BetaWindow(64,1380,240).active(at("2026-11-09T02:00:00Z"), "UTC")) }
    @Test fun fullDayAndSavedZoneWorkAcrossDst() {
        val w = BetaWindow(64, 0, 1440)
        assertTrue(w.active(at("2026-11-01T05:30:00Z"), "America/New_York"))
        assertTrue(w.active(at("2026-11-01T06:30:00Z"), "America/New_York"))
        assertFalse(w.active(at("2026-11-02T05:00:00Z"), "America/New_York"))
        assertEquals("2026-11-10", p.copy(zone="Asia/Dubai").day(at("2026-11-09T22:00:00Z")))
    }
    @Test fun appAndGroupBudgetsAreIndependentAndUseExactBoundary() {
        val q=p.copy(dailyMinutes=10, groupMinutes=15)
        assertTrue(BetaRules.reasons(q,pkg,monday,BetaUsage(599999),BetaUsage(899999)).isEmpty())
        assertEquals(1,BetaRules.reasons(q,pkg,monday,BetaUsage(600000),BetaUsage(899999)).size)
        assertEquals(1,BetaRules.reasons(q,pkg,monday,BetaUsage(1),BetaUsage(900000)).size)
        assertEquals(2,BetaRules.reasons(q,pkg,monday,BetaUsage(600000),BetaUsage(900000)).size)
    }
    @Test fun launchAllowanceAdmitsTheNthOpeningAndRejectsTheNext() {
        assertTrue(BetaRules.reasons(p.copy(launches=3),pkg,monday,BetaUsage(launches=3),BetaUsage()).isEmpty())
        assertFalse(BetaRules.reasons(p.copy(launches=3),pkg,monday,BetaUsage(launches=4),BetaUsage()).isEmpty())
    }
    @Test fun mandatoryBreakExpiresWithoutClearingDailyUsage() {
        assertEquals(1,BetaRules.reasons(p,pkg,monday,BetaUsage(),BetaUsage(pauseUntil=monday+1)).size)
        assertTrue(BetaRules.reasons(p,pkg,monday,BetaUsage(),BetaUsage(pauseUntil=monday)).isEmpty())
        assertEquals(1,BetaRules.reasons(p.copy(groupMinutes=1),pkg,monday,BetaUsage(),BetaUsage(60000,pauseUntil=monday)).size)
    }
    @Test fun bonusAppliesOnlyToItsCalendarDayAndNotShortVideoLimits() {
        val q=p.copy(dailyMinutes=1, shortMinutes=1, bonusMinutes=5, bonusDay=p.day(monday))
        assertTrue(BetaRules.reasons(q,pkg,monday,BetaUsage(60000),BetaUsage()).isEmpty())
        assertFalse(BetaRules.reasons(q,pkg,monday+86400_000,BetaUsage(60000),BetaUsage()).isEmpty())
        assertNotNull(BetaRules.shortReason(q,monday,BetaUsage(shorts=60000)))
    }
    @Test fun shortTimeAndCountLimitsRespectScheduleAndOverride() {
        val q=p.copy(shortMinutes=1, shortCount=3)
        assertNull(BetaRules.shortReason(q,monday,BetaUsage(shorts=59999,count=3)))
        assertNotNull(BetaRules.shortReason(q,monday,BetaUsage(count=4)))
        assertNotNull(BetaRules.shortReason(q,monday,BetaUsage(shorts=60000)))
        assertNull(BetaRules.shortReason(q.copy(enabled=false),monday,BetaUsage(count=99)))
        assertNull(BetaRules.shortReason(q.copy(overrideUntil=monday+1),monday,BetaUsage(count=99)))
    }
    @Test fun pomodoroBreakDoesNotLiftIndependentScheduledLock() {
        val q=p.copy(focusStart=monday,focusEnd=monday+3600_000,pomodoroMinutes=25,pomodoroBreak=5)
        assertEquals(1,BetaRules.reasons(q,pkg,monday,BetaUsage(),BetaUsage()).size)
        assertTrue(BetaRules.reasons(q,pkg,monday+25*60000,BetaUsage(),BetaUsage()).isEmpty())
        assertEquals(1,BetaRules.reasons(q,pkg,monday+30*60000,BetaUsage(),BetaUsage()).size)
        assertEquals(1,BetaRules.reasons(q.copy(block=true),pkg,monday+25*60000,BetaUsage(),BetaUsage()).size)
    }
    @Test fun focusWorksOutsideRegularHoursAndEndsExactly() {
        val q=p.copy(windows=listOf(BetaWindow(1,0,60)),focusStart=monday,focusEnd=monday+60000)
        assertFalse(q.scheduled(monday)); assertTrue(q.focus(monday)); assertFalse(q.focus(monday+60000))
    }
    @Test fun committedProfileCannotBeDisabledDeletedOrOverridden() {
        val q=p.copy(commitment=monday+86400_000, block=true, overrideUntil=monday+60000)
        assertFalse(q.accepts(q.copy(enabled=false),monday))
        assertFalse(q.accepts(q.copy(block=false),monday))
        assertFalse(q.accepts(q.copy(overrideUntil=monday+120000),monday))
        assertFalse(q.accepts(q.copy(zone="Asia/Dubai"),monday))
        assertFalse(q.accepts(q.copy(windows=listOf(BetaWindow(1,0,1))),monday))
        assertFalse(BetaConfig(true,profiles=listOf(q)).accepts(BetaConfig(false),monday))
        assertFalse(BetaConfig(true,profiles=listOf(q)).accepts(BetaConfig(true),monday))
        assertFalse(BetaRules.reasons(q,pkg,monday,BetaUsage(),BetaUsage()).isEmpty())
    }
    @Test fun commitmentsAllowOnlyRenamingAdditionalTargetsAndLongerDeadline() {
        val q=p.copy(commitment=monday+1)
        assertTrue(q.accepts(q.copy(name="New name",apps=q.apps+"com.another.app",sites=setOf("example.com"),commitment=monday+1000),monday))
        assertFalse(q.accepts(q.copy(apps=emptySet()),monday))
        assertFalse(q.accepts(q.copy(commitment=monday),monday))
        assertTrue(q.accepts(q.copy(enabled=false),monday+1))
        assertFalse(q.copy(permanent=true).accepts(q,monday+999999))
        assertTrue(q.copy(permanent=true).accepts(q.copy(permanent=true,name="Rename"),monday+999999))
    }
    @Test fun aPermanentProfileDoesNotPermanentlyFreezeUnrelatedGlobalDnsOrDisplayOptions() {
        val c=BetaConfig(true,setOf("dnsLists","grayscale"),listOf(p.copy(permanent=true)),lists=mapOf("Ads" to setOf("ads.example.com")))
        val next=c.copy(flags=emptySet(),lists=emptyMap())
        assertTrue(c.accepts(next,monday))
        assertFalse(BetaActions.tightening(c,next)) // Still requires its own delayed approval.
        assertFalse(c.accepts(next.copy(enabled=false),monday))
        assertFalse(c.accepts(next.copy(profiles=emptyList()),monday))
    }
    @Test fun overrideCannotSkipCommitmentOrFocusButExpiresNormally() {
        val q=p.copy(block=true,overrideUntil=monday+1000)
        assertTrue(BetaRules.reasons(q,pkg,monday,BetaUsage(),BetaUsage()).isEmpty())
        assertFalse(BetaRules.reasons(q,pkg,monday+1000,BetaUsage(),BetaUsage()).isEmpty())
        assertFalse(BetaRules.reasons(q.copy(permanent=true),pkg,monday,BetaUsage(),BetaUsage()).isEmpty())
        assertFalse(BetaRules.reasons(q.copy(focusStart=monday,focusEnd=monday+10000),pkg,monday,BetaUsage(),BetaUsage()).isEmpty())
    }
    @Test fun betaUnlockDoesNotClearStableReasons() {
        assertTrue(AppLockReasons(appLock=true,betaReasons=emptyList()).shouldLock)
        assertTrue(AppLockReasons(focusUntil=monday+1,betaReasons=emptyList()).shouldLock)
        assertTrue(AppLockReasons(curfewUntil=monday+1,betaReasons=emptyList()).shouldLock)
        assertTrue(AppLockReasons(betaReasons=listOf("Study")).shouldLock)
        assertFalse(AppLockReasons().shouldLock)
    }
    @Test fun pathRulesUseHostAndPathBoundariesAndDecodeEscapes() {
        assertTrue(BetaPaths.matches("youtube.com/shorts","https://www.youtube.com/shorts/123?x=1"))
        assertTrue(BetaPaths.matches("youtube.com/shorts","https://youtube.com/sh%6frts/123"))
        assertTrue(BetaPaths.matches("youtube.com/shorts","https://youtube.com:443/shorts/123"))
        assertTrue(BetaPaths.matches("youtube.com/shorts","https://person@youtube.com:443/shorts/123"))
        assertTrue(BetaPaths.matches("youtube.com/shorts","https://youtube.com/a/%2e%2e/shorts/123"))
        assertFalse(BetaPaths.matches("youtube.com/shorts","https://youtube.com/shortstop"))
        assertFalse(BetaPaths.matches("youtube.com","https://notyoutube.com"))
        assertFalse(BetaPaths.matches("youtube.com","https://youtube.com.attacker.example"))
        assertFalse(BetaPaths.matches("youtube.com","https://youtube.com@attacker.example"))
        assertFalse(BetaPaths.valid("*.example.com")); assertFalse(BetaPaths.valid("example.com:443"))
        assertTrue(BetaPaths.proxy("blob:https://example.com/1")); assertTrue(BetaPaths.proxy("https://web.archive.org/web/123"))
    }
    @Test fun conservativeChangeClassifierNeverImmediatelyRelaxesActiveRules() {
        val c=BetaConfig(true, setOf("shortBlock"),listOf(p.copy(block=true)))
        assertTrue(BetaActions.tightening(c,c.copy(flags=c.flags+"safeSearch")))
        assertTrue(BetaActions.tightening(c,c.copy(profiles=c.profiles+ p.copy(id="second"))))
        assertFalse(BetaActions.tightening(c,c.copy(enabled=false)))
        assertFalse(BetaActions.tightening(c,c.copy(flags=emptySet())))
        assertFalse(BetaActions.tightening(c,c.copy(profiles=emptyList())))
        assertFalse(BetaActions.tightening(c,c.copy(profiles=listOf(p))))
        assertFalse(BetaActions.tightening(c,c.copy(flags=c.flags+"companion")))
    }
    @Test fun invalidLimitsAndSelectorsAreRejected() {
        listOf<() -> Any>({p.copy(dailyMinutes=-1)}, {p.copy(breakMinutes=0)}, {p.copy(shortCount=-1)}, {p.copy(zone="invalid")},
            {BetaWindow(0,0,60)}, {BetaWindow(1,30,30)}, {BetaWindow(1,1440,0)},
            {BetaUiRule("com.example.app","com.other.app:id/x")}, {BetaConfig(sentence="")}).forEach { action ->
            try { action(); fail("Invalid beta configuration accepted") } catch (_: IllegalArgumentException) {} catch (_: java.time.DateTimeException) {}
        }
    }
    @Test fun sessionClockCountsAcrossAppsButDoesNotExtendAnActiveBreak() {
        val q=p.copy(sessionMinutes=1,breakMinutes=5)
        val old=BetaUsage(used=10000,session=59000)
        val hit=BetaAccounting.advance(q,old,monday-1000,monday,1000,false,false,false)
        assertEquals(monday+300000,hit.pauseUntil)
        val blocked=BetaAccounting.advance(q,hit,monday,monday+1000,0,false,false,false)
        assertEquals(hit.pauseUntil,blocked.pauseUntil)
        val resumed=BetaAccounting.advance(q,blocked,monday+1000,monday+300000,1000,false,false,false)
        assertEquals(1000,resumed.session); assertEquals(0,resumed.pauseUntil); assertEquals(12000,resumed.used)
    }
    @Test fun realIdleBreakResetsSessionButNotDailyCountersAndDeltasAreBounded() {
        val q=p.copy(sessionMinutes=10,breakMinutes=2)
        val result=BetaAccounting.advance(q,BetaUsage(used=5000,shorts=3000,count=2,launches=2,session=5000),monday-120000,monday,999999,true,true,true)
        assertEquals(2000,result.session); assertEquals(7000,result.used); assertEquals(5000,result.shorts)
        assertEquals(3,result.count); assertEquals(3,result.launches)
    }
}
