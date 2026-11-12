package com.example.installlock

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** No stable preferences, task credentials, suspension or VPN calls. Disposable database only. */
class BetaPersistenceTest {
    private val c = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "beta_test_${System.nanoTime()}.db"
    private val helper = BetaDatabase(c, name)
    private fun read() = helper.writableDatabase.rawQuery("SELECT body FROM config WHERE id=1",null).use { it.moveToFirst(); BetaJson.decode(it.getString(0)) }
    @After fun close() { helper.close(); c.deleteDatabase(name) }
    @Test fun defaultsAndConfigRoundTripAreIsolatedFromStableSettings() {
        assertEquals(BetaConfig(),read())
        val p = BetaProfile("test", "Study", setOf("com.example.test"), setOf("example.com/path"), true,
            "Asia/Dubai", listOf(BetaWindow(65,1380,240)), dailyMinutes=30,groupMinutes=60,sessionMinutes=10,breakMinutes=20,
            launches=5, shortMinutes=2,shortCount=10,gray=true,stretchMinutes=30,commitment=2000,
            focusStart=100,focusEnd=1000,bonusDay="2026-11-12",bonusMinutes=5)
        val conf = BetaConfig(true,setOf("counter"),listOf(p),listOf(BetaUiRule("com.example.test","com.example.test:id/feed")),setOf("com.example.test"),mapOf("Ads" to setOf("ads.example.com")))
        assertEquals(conf,BetaJson.decode(BetaJson.encode(conf)))
        assertTrue(BetaTransactions.save(helper.writableDatabase,conf,1))
        assertEquals(conf.copy(revision=1),read())
        assertFalse(c.getSharedPreferences("gouge_beta_marker",0).getBoolean("test_enabled",false))
    }
    @Test fun staleDraftCannotOverwriteMoreRecentPolicyAndHistoryIsRetained() {
        val d=helper.writableDatabase
        val first=BetaConfig(true)
        assertTrue(BetaTransactions.save(d,first,100))
        assertFalse(BetaTransactions.save(d,first.copy(enabled=false),101))
        assertTrue(read().enabled); assertEquals(1,read().revision)
        d.rawQuery("SELECT body FROM history WHERE revision=0",null).use { assertTrue(it.moveToFirst()); assertEquals(BetaConfig(),BetaJson.decode(it.getString(0))) }
    }
    @Test fun commitmentCannotBeRelaxedAtTransactionExecutionEvenWithCurrentRevision() {
        val p=BetaProfile("study","Study",setOf("com.example.test"),enabled=true,block=true,commitment=10000)
        val conf=BetaConfig(true,profiles=listOf(p))
        assertTrue(BetaTransactions.save(helper.writableDatabase,conf,1))
        assertFalse(BetaTransactions.save(helper.writableDatabase,read().copy(profiles=emptyList()),2))
        assertFalse(BetaTransactions.save(helper.writableDatabase,read().copy(enabled=false),2))
        assertEquals(listOf(p),read().profiles)
        assertTrue(BetaTransactions.save(helper.writableDatabase,read().copy(profiles=emptyList()),10000))
        helper.writableDatabase.rawQuery("SELECT pkg FROM managed",null).use { assertTrue(it.moveToFirst()); assertEquals("com.example.test",it.getString(0)) }
    }
    @Test fun competingSavesCannotBothWinTheSameRevision() {
        val d=helper.writableDatabase; val start=CountDownLatch(1); val done=CountDownLatch(2); val won=AtomicInteger()
        repeat(2) { i -> Thread {
            try { start.await(); if (BetaTransactions.save(d,BetaConfig(true,flags=setOf("flag$i")),1)) won.incrementAndGet() }
            finally { done.countDown() }
        }.start() }
        start.countDown(); assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS)); assertEquals(1,won.get()); assertEquals(1,read().revision)
    }
    @Test fun replayCounterIsAtomicAndSurvivesDatabaseReopen() {
        val d=helper.writableDatabase; d.execSQL("INSERT INTO peer VALUES('peer','test-ciphertext',0)")
        assertFalse(BetaTransactions.consume(d,"unknown",1)); assertFalse(BetaTransactions.consume(d,"peer",0))
        assertTrue(BetaTransactions.consume(d,"peer",1)); assertFalse(BetaTransactions.consume(d,"peer",1))
        assertTrue(BetaTransactions.consume(d,"peer",5)); assertFalse(BetaTransactions.consume(d,"peer",4))
        helper.close()
        assertFalse(BetaTransactions.consume(helper.writableDatabase,"peer",5)); assertTrue(BetaTransactions.consume(helper.writableDatabase,"peer",6))
    }
    @Test fun peerCiphertextIsBoundToItsIdentity() {
        val ciphertext=BetaKeys.seal("test:one","not-a-real-pairing-secret")
        assertEquals("not-a-real-pairing-secret",BetaKeys.open("test:one",ciphertext))
        try { BetaKeys.open("test:two",ciphertext); fail("Wrong peer accepted") } catch (_: java.security.GeneralSecurityException) {}
        assertFalse(ciphertext.contains("not-a-real-pairing-secret"))
    }
    @Test fun independentRecoveryContinuesAfterAFailedStep() {
        val ran=mutableListOf<String>()
        BetaEnforcement.recover("test failure" to { ran.add("first"); throw IllegalStateException("Synthetic test failure") }, "next protection" to { ran.add("second") })
        assertEquals(listOf("first","second"),ran)
    }
    @Test fun betaOffDoesNotChangeStableChromePoliciesOrUnknownManagedKeys() {
        val names=mutableSetOf<String>(); val prefix="beta_off_test_${System.nanoTime()}_"
        val isolated=object : android.content.ContextWrapper(c) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                names.add(name); return c.getSharedPreferences(prefix+name,mode)
            }
        }
        try {
            val current=android.os.Bundle().apply { putString("UnrelatedPolicy","preserve me"); putBoolean("ForceGoogleSafeSearch",false); putInt("ForceYouTubeRestrict",1) }
            val stable=ChromeRestrictions.update(current,true,listOf("example.com"))!!
            val combined=BetaChrome.update(isolated,current,stable)
            assertTrue(BetaChrome.equal(stable,combined)); assertFalse(BetaStore.enabled(isolated))
            assertEquals("preserve me",combined.getString("UnrelatedPolicy"))
        } finally { names.forEach { c.deleteSharedPreferences(prefix+it) } }
    }
}
