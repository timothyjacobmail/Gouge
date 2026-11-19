package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class BrowserHandlerRulesTest {
    @Test fun automateTextHandlerDoesNotEnrollOrKeepAutomateInTheDisconnectedGuard() {
        val automate = "com.llamalab.automate"
        val browser = "com.android.chrome"
        assertFalse(BrowserHandlerRules.isBrowserHandler(automate, "$automate.ProcessTextActivity"))
        assertTrue(BrowserHandlerRules.isBrowserHandler(browser, "$browser.IntentDispatcher"))
        assertTrue(BrowserHandlerRules.isBrowserHandler(automate, "$automate.OtherBrowserActivity"))
        assertEquals(setOf(browser), BrowserHandlerRules.rememberedBrowsers(setOf(automate, browser)))
    }
}
