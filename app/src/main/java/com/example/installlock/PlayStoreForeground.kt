package com.example.installlock

/** Vivo overlays may leave no application root. Keep package identity from window transitions. */
internal object PlayStoreForeground {
    fun application(pkg: String?, keyboard: String?): String? = pkg?.takeUnless {
        it.isBlank() || it == "android" || it == "com.android.systemui" ||
            it == "com.vivo.systemuiplugin" || it == keyboard
    }

    fun resolve(event: String?, root: String?, previous: String?, keyboard: String?): String? {
        return application(event, keyboard) ?: application(root, keyboard) ?: application(previous, keyboard)
    }
}

/** A focused-window change can recover a missed state event; ordinary polls cannot revive stale roots. */
internal class PlayStoreForegroundState {
    var previous: String? = null
        private set
    private var hasTransition = false

    fun reset() { previous = null; hasTransition = false }

    fun resolve(event: String?, root: String?, keyboard: String?, focusChanged: Boolean = false): String? {
        val app = PlayStoreForeground.application(event, keyboard)
        val currentRoot = root.takeIf { !hasTransition || focusChanged }
        val next = PlayStoreForeground.resolve(app, currentRoot, previous, keyboard)
        if (app != null || (focusChanged && PlayStoreForeground.application(root, keyboard) != null)) hasTransition = true
        previous = next
        return next
    }
}
