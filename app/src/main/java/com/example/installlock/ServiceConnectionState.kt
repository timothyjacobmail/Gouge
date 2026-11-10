package com.example.installlock

/** A late disconnect from an older service must not clear a newer connection. */
internal class ServiceConnectionState {
    private var owner: Any? = null
    val connected: Boolean @Synchronized get() = owner != null
    @Synchronized fun connect(service: Any) { owner = service }
    @Synchronized fun owns(service: Any) = owner === service
    @Synchronized fun disconnect(service: Any): Boolean {
        if (owner !== service) return false
        owner = null
        return true
    }
}
