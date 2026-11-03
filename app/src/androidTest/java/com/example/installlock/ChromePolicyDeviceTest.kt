package com.example.installlock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reconciles the saved configuration without adding or changing any approvals. */
@RunWith(AndroidJUnit4::class)
class ChromePolicyDeviceTest {
    @Test fun savedDomainAllowsReachAndroidWithoutChangingWebsiteApprovals() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(Policy.isOwner(context) && Store.chromeLock(context))
        val savedRules = Store.domainRules(context)
        val savedSites = Store.sites(context)
        val extras = Store.extraLockedSites(context).map { it.domain }

        Policy.syncChrome(context, force = true)
        val manager = context.getSystemService(DevicePolicyManager::class.java)
        val policy = manager.getApplicationRestrictions(ComponentName(context, AdminReceiver::class.java), Policy.CHROME)
        val allowed = JSONArray(policy.getString("URLAllowlist")).let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        val domainApprovals = ExtraSiteLocks(extras.toSet()).allowedPatterns(ChromeDomainApprovals(savedRules).policyPatterns())
        assertTrue("Saved domain Allow rules must reach Chrome's managed policy", allowed.containsAll(domainApprovals))
        assertEquals("*", JSONArray(policy.getString("URLBlocklist")).getString(0))
        assertEquals(savedRules, Store.domainRules(context))
        assertEquals(savedSites, Store.sites(context))
    }
}
