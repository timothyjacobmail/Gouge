package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class PlayStoreForegroundTest {
    private val store = "com.android.vending"
    private val home = "com.android.launcher3"
    private val keyboard = "com.example.keyboard"
    @Test fun windowEventDetectsPlayStoreWhenVivoHidesApplicationRoots() {
        assertEquals(store, PlayStoreForeground.resolve(store, null, home, keyboard))
        assertEquals(store, PlayStoreForeground.resolve(null, null, store, keyboard))
    }
    @Test fun leavingPlayStoreIsRecognizedWithoutALauncherRoot() {
        assertEquals(home, PlayStoreForeground.resolve(home, null, store, keyboard))
    }
    @Test fun systemOverlaysAndKeyboardDoNotEndAnAppSession() {
        assertEquals(store, PlayStoreForeground.resolve("com.android.systemui", null, store, keyboard))
        assertEquals(store, PlayStoreForeground.resolve(keyboard, null, store, keyboard))
        assertEquals(store, PlayStoreForeground.resolve(null, "android", store, keyboard))
        assertNull(PlayStoreForeground.resolve("android", null, null, keyboard))
    }
    @Test fun availableRootIsUsedWhenThereIsNoWindowEvent() {
        assertEquals(store, PlayStoreForeground.resolve(null, store, home, keyboard))
        assertEquals(home, PlayStoreForeground.resolve(home, store, store, keyboard))
    }

    @Test fun vivoSystemPluginCannotBecomeTheForegroundApplication() {
        assertEquals(store, PlayStoreForeground.resolve("com.vivo.systemuiplugin", null, store, keyboard))
        assertEquals(home, PlayStoreForeground.resolve(null, "com.vivo.systemuiplugin", home, keyboard))
        assertNull(PlayStoreForeground.resolve("com.vivo.systemuiplugin", null, null, keyboard))
    }

    @Test fun focusChangeRecoversAMissedPlayStoreWindowStateEvent() {
        val state = PlayStoreForegroundState()
        assertEquals(home, state.resolve(home, null, keyboard))
        assertEquals(store, state.resolve(null, store, keyboard, focusChanged = true))
        assertEquals(store, state.resolve("com.vivo.systemuiplugin", null, keyboard))
    }

    @Test fun ordinaryPollDoesNotReviveVivosStalePlayStoreRootAfterHome() {
        val state = PlayStoreForegroundState()
        state.resolve(store, null, keyboard)
        assertEquals(home, state.resolve(home, store, keyboard))
        repeat(10) { assertEquals(home, state.resolve(null, store, keyboard)) }
    }

    @Test fun restartingMonitorDiscardsCachedForegroundAndReadsVisibleStore() {
        val state = PlayStoreForegroundState()
        state.resolve(home, null, keyboard)
        state.reset()
        assertNull(state.previous)
        assertEquals(store, state.resolve(null, store, keyboard))
    }

    @Test fun focusedHomeWindowEndsSessionEvenIfItsStateEventWasMissed() {
        val state = PlayStoreForegroundState()
        state.resolve(store, null, keyboard)
        assertEquals(home, state.resolve(null, home, keyboard, focusChanged = true))
    }

    @Test fun transientFocusedSystemWindowDoesNotReplaceAnApplication() {
        val state = PlayStoreForegroundState()
        state.resolve(store, null, keyboard)
        assertEquals(store, state.resolve(null, "com.vivo.systemuiplugin", keyboard, focusChanged = true))
        assertEquals(store, state.resolve(null, keyboard, keyboard, focusChanged = true))
        assertEquals(store, state.resolve(null, null, keyboard, focusChanged = true))
    }
}
