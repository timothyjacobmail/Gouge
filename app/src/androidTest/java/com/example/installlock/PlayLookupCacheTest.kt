package com.example.installlock

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Only isolated metadata. Does not fetch listings, alter real app policies or change bank selections. */
class PlayLookupCacheTest {
    private val base=InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix="play_cache_test_${System.nanoTime()}_"
    private val names=mutableSetOf<String>()
    private val c=object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String,mode: Int): SharedPreferences {
            names.add(name); return base.getSharedPreferences(prefix+name,mode)
        }
    }
    private fun pkg()=PackageInfo().apply { packageName="test.metadata.app"; firstInstallTime=1; lastUpdateTime=2 }
    @After fun clean() { names.forEach { base.deleteSharedPreferences(prefix+it) } }
    @Test fun oldVerifiedCategoryIsReusedWithoutCreatingAnyNetworkBudgetOrRequest() {
        c.getSharedPreferences("play_categories",0).edit().putString("test.metadata.app","1|2|FINANCE|1").commit()
        repeat(5) { assertEquals(PlayInstallCategory.FINANCE,PlayCategoryLookup.category(c,pkg(),background=true)) }
        val saved=PlayCategoryRecord.decode(c.getSharedPreferences("play_categories",0).getString("test.metadata.app",null))!!
        assertFalse(saved.legacy); assertEquals(Long.MAX_VALUE,saved.retryAt)
        assertFalse("play_lookup_network" in names)
    }
    @Test fun legacyFailureGetsOnePersistentFloorInsteadOfImmediateRetriesOnEverySweep() {
        c.getSharedPreferences("play_categories",0).edit().putString("test.metadata.app","1|2||1").commit()
        val start=System.currentTimeMillis()
        assertNull(PlayCategoryLookup.category(c,pkg(),background=true))
        val first=c.getSharedPreferences("play_categories",0).getString("test.metadata.app",null)
        repeat(5) { assertNull(PlayCategoryLookup.category(c,pkg(),background=true)) }
        val saved=PlayCategoryRecord.decode(first)!!
        assertTrue(saved.retryAt>=start+PlayLookupPolicy.MIN_RETRY_MS)
        assertEquals(first,c.getSharedPreferences("play_categories",0).getString("test.metadata.app",null))
        assertFalse("play_lookup_network" in names)
    }
}
