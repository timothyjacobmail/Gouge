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
}
