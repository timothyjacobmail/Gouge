package com.example.installlock

/** Vivo overlays may leave no application root. Keep package identity from window transitions. */
internal object PlayStoreForeground {
    fun resolve(event: String?, root: String?, previous: String?, keyboard: String?): String? {
        fun application(pkg: String?) = pkg?.takeUnless {
            it == "android" || it == "com.android.systemui" || it == keyboard
        }
        return application(event) ?: application(root) ?: application(previous)
    }
}
