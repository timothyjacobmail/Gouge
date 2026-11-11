package com.example.installlock

import android.content.Context

internal object TaskPasswords {
    private fun prefs(c: Context) = c.getSharedPreferences("install_lock", Context.MODE_PRIVATE)
    internal fun vault(c: Context) = TaskVault(object : TaskData {
        override fun get(key: String): String? = try { prefs(c).getString(key, null) }
            catch (_: ClassCastException) { null }
        override fun read() = prefs(c).all.filterKeys { it.startsWith("task.") }.mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }.toMap()
        override fun write(changes: Map<String, String>): Boolean {
            if (Thread.currentThread().isInterrupted) return false
            val edit = prefs(c).edit()
            if (changes["task.ready"] == "1" && !ready(c)) {
                // The switch to scoped credentials and retirement of old broad credentials is atomic.
                for (role in listOf(Store.Pw.FILTER, Store.Pw.SETTINGS, Store.Pw.APP_LOCK)) {
                    for (suffix in listOf("salt", "hash", "fails", "lockoutUntil")) edit.remove(role.prefix + suffix)
                }
                edit.putLong("unlockedUntil", 0L)
            }
            changes.forEach { (key, value) -> edit.putString(key, value) }
            return edit.commit()
        }
    }, lockout = { Store.timingConfig(c).passwordLockoutMs(it) },
        encryptPassword = { scope, password -> NativeTaskPasswords.encrypt(c, scope, password) })

    fun ready(c: Context) = prefs(c).getString("task.ready", "") == "1"
    fun has(c: Context, scope: TaskScope) = vault(c).has(scope)
    fun remaining(c: Context, scope: TaskScope) = vault(c).remaining(scope)
    fun scopes(c: Context) = vault(c).scopes()
    @Synchronized fun setupPlayStoreDelay(c: Context): String? {
        val scope = TaskScope(TaskKind.PLAY_STORE_DELAY)
        if (!ready(c)) return "Set up task passwords first."
        if (!has(c, scope)) {
            val password = TaskVault.generatePassword(scope)
            vault(c).installPlayStoreDelay(password)?.let { return it }
        }
        val value = NativeTaskPasswords.password(c, scope) ?: return "The current task password must be attached before setup can finish."
        val secrets = com.example.delayedsecrect.SecretStore(c)
        if (secrets.getAll().none { it.value == value }) secrets.add("Play Store opening delay", value, 2)
        return null
    }
    fun required(c: Context) = TaskScope.global + Store.protectedApps(c).sorted().flatMap { TaskScope.appTasks(it) }
    @Synchronized fun check(c: Context, scope: TaskScope, password: String) = vault(c).check(scope, password)
    @Synchronized fun setupReddit(c: Context, items: List<NewTaskPassword>, share: Boolean): String? =
        if (RedditStore.enabled(c)) "Reddit protection is already enabled. Existing passwords cannot be replaced."
        else vault(c).installReddit(items, share)

    @Synchronized fun migrate(c: Context, legacy: Map<Store.Pw, String>, items: List<NewTaskPassword>, share: Boolean): String? {
        if (ready(c)) return "Task passwords have already been set up."
        if (items.map { it.scope }.toSet() != required(c).toSet()) return "The protected app list changed. Reopen setup and try again."
        for (role in Store.Pw.entries.filter { Store.hasPassword(c, it) }) {
            if (!Store.checkPassword(c, legacy[role].orEmpty(), role)) return "Wrong current ${role.name.lowercase().replace('_', '-')} password, or that password is locked out."
        }
        if (items.any { it.password in legacy.values }) return "New task passwords must differ from your old broad passwords and main password."
        if (Thread.currentThread().isInterrupted) return "Setup cancelled."
        return vault(c).install(items, share)
    }
    @Synchronized fun run(c: Context, scope: TaskScope, password: String, action: () -> Boolean): Boolean =
        vault(c).check(scope, password) && !Thread.currentThread().isInterrupted && action()

    @Synchronized fun change(c: Context, scope: TaskScope, old: String, password: String, share: Boolean): String? {
        return vault(c).change(scope, old, password, share)
    }
    @Synchronized fun enroll(c: Context, app: String, password: String, items: List<NewTaskPassword>, share: Boolean): String? {
        if (app !in Store.protectedApps(c)) return "Lock the app before setting up its passwords."
        if (items.any { it.scope.app != app }) return "All credentials must belong to the selected app."
        return vault(c).enroll(items, password, share)
    }
    @Synchronized fun setLimit(c: Context, app: String, minutes: Int, password: String) = vault(c).setLimit(app, minutes, password)
    @Synchronized fun lockApp(c: Context, app: String) = vault(c).lock(app)
}
