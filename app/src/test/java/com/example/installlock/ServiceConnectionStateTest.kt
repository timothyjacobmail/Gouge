package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class ServiceConnectionStateTest {
    @Test fun disconnectRevokesTheCurrentConnection() {
        val state = ServiceConnectionState()
        val service = Any()
        assertFalse(state.connected)
        state.connect(service)
        assertTrue(state.owns(service))
        assertTrue(state.disconnect(service))
        assertFalse(state.connected)
    }
    @Test fun anOlderUnbindCannotDisconnectOrDetachTheReplacementService() {
        val state = ServiceConnectionState()
        val old = Any(); val current = Any()
        state.connect(old); state.connect(current)
        assertFalse(state.disconnect(old))
        assertTrue(state.connected)
        assertTrue(state.owns(current))
        assertFalse(state.owns(old))
    }
    @Test fun repeatedCleanupCannotAffectANewerConnection() {
        val state = ServiceConnectionState()
        val old = Any(); val current = Any()
        state.connect(old); assertTrue(state.disconnect(old))
        state.connect(current)
        assertFalse(state.disconnect(old))
        assertTrue(state.owns(current))
        assertTrue(state.disconnect(current))
    }
}
