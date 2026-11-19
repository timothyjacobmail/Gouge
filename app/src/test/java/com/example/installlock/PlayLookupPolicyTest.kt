package com.example.installlock

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class PlayLookupPolicyTest {
    @Test fun verifiedInstalledVersionDoesNotRefetchJustBecauseADayHasPassed() {
        val p=PlayCategoryRecord(1,2,"FINANCE",10)
        assertFalse(PlayLookupPolicy.due(p,100_000_000_000))
        assertEquals(PlayInstallCategory.FINANCE,PlayCategoryRules.cachedCategory(p,1,2))
        assertNull(PlayCategoryRules.cachedCategory(p,1,3))
        assertTrue(PlayLookupPolicy.due(null,100))
    }
    @Test fun oldOneMinuteFailureGetsAOneTimePersistedFifteenMinuteFloor() {
        val old=PlayCategoryRecord.decode("1|2||60")!!
        val migrated=PlayLookupPolicy.migrate(old,1000)
        assertEquals(901000,migrated.retryAt)
        assertEquals(1,migrated.failures)
        assertFalse(PlayLookupPolicy.due(migrated,900999))
        assertTrue(PlayLookupPolicy.due(migrated,901000))
        val reloaded=PlayCategoryRecord.decode(migrated.encode())!!
        assertFalse(reloaded.legacy)
        assertEquals(migrated,PlayLookupPolicy.migrate(reloaded,1_000_000))
    }
    @Test fun successfulLegacyCacheIsMigratedWithoutAnyDownloadOrChangedCategory() {
        val old=PlayCategoryRecord.decode("1|2|FINANCE|10")!!
        val next=PlayLookupPolicy.migrate(old,100)
        assertEquals("FINANCE",next.category); assertEquals(Long.MAX_VALUE,next.retryAt)
        assertFalse(next.legacy); assertEquals(0,next.failures)
    }
    @Test fun failuresBackOffAndRemainBoundedAcrossRestart() {
        val now=10000L
        var p: PlayCategoryRecord?=null
        val delays=listOf(15,60,360,1440,1440)
        delays.forEachIndexed { i,minutes ->
            p=PlayCategoryRecord.decode(PlayLookupPolicy.completed(1,2,p,null,now).encode())
            assertEquals(i+1,p!!.failures)
            assertEquals(now+minutes*60_000L,p!!.retryAt)
        }
        val success=PlayLookupPolicy.completed(1,2,p,"SHOPPING",now)
        assertEquals(0,success.failures); assertEquals(Long.MAX_VALUE,success.retryAt)
        val missing=PlayLookupPolicy.completed(1,2,null,null,now,unavailable=true)
        assertEquals(now+7*86400_000L,missing.retryAt)
    }
    @Test fun unknownCategoryNeverReceivesAStableExemption() {
        val p=PlayLookupPolicy.completed(1,2,null,"NEW_UNRECOGNIZED_CATEGORY",100)
        assertEquals("",p.category); assertFalse(PlayLookupPolicy.verified(p)); assertFalse(PlayLookupPolicy.due(p,101))
        assertNull(PlayCategoryRules.cachedCategory(p,1,2))
    }
    @Test fun failureCounterCannotOverflowAndMalformedRecordsAreRejected() {
        val p=PlayLookupPolicy.completed(1,2,PlayCategoryRecord(1,2,"",0,100),null,100)
        assertEquals(100,p.failures); assertEquals(100+86400_000L,p.retryAt)
        listOf("1|2||3|-1","1|2||3|101","1|2||3|nope","1|2||3|1|extra").forEach { assertNull(PlayCategoryRecord.decode(it)) }
    }
    @Test fun sharedBackgroundBudgetLeavesRoomForNewInstallRequests() {
        val p=PlayLookupAllowance(100,16,0)
        assertFalse(p.permits(101,true)); assertTrue(p.permits(101,false))
        assertFalse(p.copy(requests=24).permits(101,false))
        assertFalse(p.copy(requests=0,bytes=PlayLookupAllowance.MAX_BYTES).permits(101,false))
        assertTrue(p.copy(requests=24,bytes=PlayLookupAllowance.MAX_BYTES).permits(100+PlayLookupAllowance.WINDOW_MS,true))
        assertEquals(PlayLookupAllowance(99),p.current(99))
    }
    @Test fun limitedInputCountsEveryByteIncludingSingleByteAndSkipWithoutReadingPastTheCap() {
        val raw=ByteArrayInputStream(ByteArray(100){it.toByte()}); var read=0
        val stream=LimitedNetworkInput(raw,10){read+=it}
        assertEquals(0,stream.read()); assertEquals(4,stream.skip(4))
        val buffer=ByteArray(100); assertEquals(5,stream.read(buffer,0,100)); assertEquals(10,read); assertEquals(90,raw.available())
        try { stream.read(); fail("Cap was exceeded") } catch (_: IOException) {}
        assertEquals(90,raw.available())
    }
    @Test fun readerStopsAtMetadataInsteadOfDownloadingTheRestOfThePage() {
        val script=ByteArrayInputStream(("<script type='application/ld+json'>metadata-complete</script>"+"x".repeat(1_000_000)).toByteArray())
        assertEquals("FINANCE",PlayListingReader.category(script){if(it.contains("metadata-complete</script>")) "FINANCE" else null})
        assertTrue(script.available()>990_000)
    }
    @Test fun decodedHtmlIsBoundedEvenForHighlyCompressedOrUnclassifiableResponses() {
        val raw=ByteArrayInputStream(ByteArray(2*PlayListingReader.MAX_HTML_BYTES){'x'.code.toByte()})
        assertNull(PlayListingReader.category(raw){null})
        assertEquals(PlayListingReader.MAX_HTML_BYTES,raw.available())
    }
    @Test fun scannerSkipsLargeInlineScriptsAndFindsLateMetadataWithBoundedRetainedText() {
        val html="<script>"+"x".repeat(1_150_000)+"</script><SCRIPT TYPE=\"application/ld+json\">own-metadata</SCRIPT>"+"x".repeat(200000)
        val input=ByteArrayInputStream(html.toByteArray())
        assertEquals("FINANCE",PlayListingReader.category(input){if(it.contains("own-metadata")) "FINANCE" else null})
        assertTrue(input.available()>190000)
    }
}
