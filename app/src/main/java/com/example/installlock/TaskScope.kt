package com.example.installlock

enum class TaskKind(val title: String, val perApp: Boolean = false) {
    WHITELIST_ADD("Add Chrome whitelist sites"),
    WHITELIST_OFF("Turn off Chrome whitelist"),
    REDDIT_ALLOW("Add allowed subreddits"),
    REDDIT_OFF("Relax Reddit app protection"),
    KEYWORD_OFF("Turn off keyword filtering"),
    WORD_EXCEPTION("Add per-word exceptions"),
    PAGE_EXCEPTION("Add generic page exceptions"),
    DOMAIN_ALLOW("Add Allow domain rules"),
    DOMAIN_MOVE("Reorder domain rules"),
    DOMAIN_REMOVE("Remove Block domain rules"),
    VPN_OFF("Turn off Google VPN"),
    DNS_UNBLOCK("Unblock Google DNS domains"),
    NEVER_REMOVE("Remove never-install rules"),
    UNINSTALL_REMOVE("Remove uninstall and force-stop protection"),
    CURBOX_OFF("Turn off Curbox guard"),
    SCREENSHOTS_ALLOW("Allow screenshots"),
    TIMING("Change durations and waiting periods"),
    CATEGORIES("Change immediate-access categories"),
    PLAY_STORE_DELAY("Change Play Store opening delay"),
    SECURITY("Change password lockout settings"),
    ENROLL_APP("Set up passwords for newly locked apps"),
    APP_UNLOCK("Temporary access", true),
    APP_REMOVE("Remove permanent app lock", true),
    APP_LIMIT("Change temporary-access limit", true)
}

data class TaskScope(val kind: TaskKind, val app: String = "") {
    init {
        require(if (kind.perApp) Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(app) else app.isEmpty())
    }
    val id: String get() = "${kind.name}|$app"
    val title: String get() = if (app.isEmpty()) kind.title else "${kind.title}: $app"
    companion object {
        fun decode(value: String): TaskScope? = try {
            val parts = value.split('|')
            if (parts.size != 2) null else TaskScope(TaskKind.valueOf(parts[0]), parts[1])
        } catch (_: IllegalArgumentException) { null }
        fun appTasks(app: String) = listOf(TaskKind.APP_UNLOCK, TaskKind.APP_REMOVE, TaskKind.APP_LIMIT).map { TaskScope(it, app) }
        val global: List<TaskScope> get() = TaskKind.entries.filterNot { it.perApp }.map { TaskScope(it) }
    }
}

data class NewTaskPassword(val scope: TaskScope, val password: String, val limitMinutes: Int? = null)

internal interface TaskData {
    fun read(): Map<String, String>
    fun get(key: String): String? = read()[key]
    fun write(changes: Map<String, String>): Boolean
}

/** No signed-in task session or reusable authorization token exists. Each operation checks its exact scope. */
internal class TaskVault(private val data: TaskData, private val now: () -> Long = System::currentTimeMillis,
                         private val lockout: (Int) -> Long = { fails -> if (fails < 5) 0 else 60_000L },
                         private val encryptPassword: ((TaskScope, String) -> String)? = null) {
    companion object {
        private val verifiedPasswords = PasswordVerificationCache()
        private fun generatedPrefix(scope: TaskScope) = "IL-" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(scope.id.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) } + "-A1"
        fun generatePassword(scope: TaskScope): String = generatedPrefix(scope) + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(24).also { java.security.SecureRandom().nextBytes(it) })
        private fun isSeparatePassword(scope: TaskScope, password: String) = password.startsWith(generatedPrefix(scope)) &&
            Regex("[A-Za-z0-9_-]{32}").matches(password.removePrefix(generatedPrefix(scope)))
    }
    private fun prefix(scope: TaskScope) = "task.${scope.id}."
    fun ready() = data.get("task.ready") == "1"
    fun scopes() = data.get("task.scopes").orEmpty().lines().mapNotNull { TaskScope.decode(it) }.toSet()
    fun has(scope: TaskScope) = data.get(prefix(scope) + "hash") != null
    fun remaining(scope: TaskScope) = ((data.get(prefix(scope) + "lockout")?.toLongOrNull() ?: 0L) - now()).coerceAtLeast(0)
    fun limit(app: String) = data.get(prefix(TaskScope(TaskKind.APP_UNLOCK, app)) + "limit")?.toIntOrNull() ?: 0
    fun until(app: String) = if (ready()) data.get(prefix(TaskScope(TaskKind.APP_UNLOCK, app)) + "until")?.toLongOrNull() ?: 0L else 0L
    private fun hash(password: String, salt: ByteArray) = PasswordKdf.derive(password, salt)
    private fun encoded(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)
    private fun matches(scope: TaskScope, password: String): Boolean {
        if (Thread.currentThread().isInterrupted) return false
        return try {
        val salt = data.get(prefix(scope) + "salt") ?: return false
        val expected = data.get(prefix(scope) + "hash") ?: return false
        verifiedPasswords.matches(scope.id, salt, expected, password) {
            java.security.MessageDigest.isEqual(java.util.Base64.getDecoder().decode(expected),
                hash(password, java.util.Base64.getDecoder().decode(salt)))
        }
        } catch (_: IllegalArgumentException) { false }
    }
    /** Match local vault entries without counting unrelated saved secrets as failed attempts. */
    fun matchesStoredSecret(scope: TaskScope, password: String): Boolean =
        ready() && password.isNotBlank() && has(scope) && matches(scope, password)
    private fun record(item: NewTaskPassword): Map<String, String> {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val key = prefix(item.scope)
        return mutableMapOf(key + "salt" to encoded(salt), key + "hash" to encoded(hash(item.password, salt)),
            key + "fails" to "0", key + "lockout" to "0").apply {
            item.limitMinutes?.let { put(key + "limit", it.toString()) }
            encryptPassword?.let { put(key + "secret", it(item.scope, item.password)) }
        }
    }
    private fun validation(items: List<NewTaskPassword>, share: Boolean): String? = when {
        items.isEmpty() || items.map { it.scope }.distinct().size != items.size -> "Each task needs exactly one password."
        items.any { !StorePasswordRules.valid(it.password) } -> "Use 8 or more characters, including a letter and a number."
        items.any { if (it.scope.kind == TaskKind.APP_UNLOCK) it.limitMinutes !in 1..10080 else it.limitMinutes != null } -> "Choose an app access limit from 1 to 10080 minutes."
        !share && items.map { it.password }.distinct().size != items.size -> "Two tasks have the same password. Use different passwords or explicitly allow sharing."
        else -> null
    }
    fun install(items: List<NewTaskPassword>, share: Boolean): String? {
        if (ready()) return "Task passwords have already been set up."
        validation(items, share)?.let { return it }
        val changes = mutableMapOf("task.ready" to "1", "task.scopes" to items.joinToString("\n") { it.scope.id })
        for (item in items) {
            if (Thread.currentThread().isInterrupted) return "Setup cancelled."
            changes.putAll(record(item))
        }
        return if (data.write(changes)) null else "Could not confirm passwords were saved. Reopen setup to check."
    }
    /** Upgrade-only enrollment: these new scopes cannot replace any existing credential. */
    fun installReddit(items: List<NewTaskPassword>, share: Boolean): String? {
        if (!ready()) return "Set up task passwords first."
        validation(items, share)?.let { return it }
        val missing = RedditRules.credentialKinds.map { TaskScope(it) }.filterNot(::has).toSet()
        if (missing.isEmpty() || items.map { it.scope }.toSet() != missing)
            return "Only missing Reddit task passwords can be set up here."
        if (!share && items.any { !isSeparatePassword(it.scope, it.password) })
            return "Generate separate Reddit passwords, or explicitly permit chosen passwords and possible sharing."
        val changes = mutableMapOf("task.scopes" to (scopes() + missing).joinToString("\n") { it.id })
        items.forEach { changes.putAll(record(it)) }
        return if (data.write(changes)) null else "Could not save Reddit task passwords."
    }
    fun check(scope: TaskScope, password: String): Boolean {
        if (!ready() || password.isBlank() || !has(scope) || remaining(scope) > 0) return false
        val key = prefix(scope)
        if (matches(scope, password)) return data.write(mapOf(key + "fails" to "0"))
        val fails = ((data.read()[key + "fails"]?.toLongOrNull() ?: 0L) + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        data.write(mapOf(key + "fails" to fails.toString(), key + "lockout" to (now() + lockout(fails)).toString()))
        return false
    }
    /** Generates only this newly introduced credential; existing passwords cannot be replaced. */
    fun installPlayStoreDelay(password: String): String? {
        val scope = TaskScope(TaskKind.PLAY_STORE_DELAY)
        if (!ready()) return "Set up task passwords first."
        if (has(scope)) return "Play Store delay password is already configured."
        if (!isSeparatePassword(scope, password)) return "Use a generated password for this task."
        val changes = record(NewTaskPassword(scope, password)).toMutableMap()
        changes["task.scopes"] = (scopes() + scope).joinToString("\n") { it.id }
        changes[prefix(scope) + "delayMinutes"] = "2"
        return if (data.write(changes)) null else "Could not save Play Store delay password."
    }
    fun change(scope: TaskScope, old: String, password: String, share: Boolean): String? {
        if (!StorePasswordRules.valid(password)) return "Use 8 or more characters, including a letter and a number."
        if (!check(scope, old)) return "Wrong password for this task, or this task is locked out."
        // Never test a candidate against other roles: that would let one task probe their secrets.
        if (!share && !isSeparatePassword(scope, password)) return "Generate a separate password, or explicitly permit a chosen password and possible sharing."
        return if (data.write(record(NewTaskPassword(scope, password)))) null else "Could not save password."
    }
    /** Completion of the persisted one-time subreddit-add migration wait; never replaces native credentials. */
    internal fun recoverLegacySubredditPassword(expectedHash: String, password: String): Boolean {
        val scope = TaskScope(TaskKind.REDDIT_ALLOW)
        val key = prefix(scope)
        if (!ready() || encryptPassword == null || data.get(key + "secret") != null || remaining(scope) > 0 ||
            expectedHash.isEmpty() || data.get(key + "hash") != expectedHash || !isSeparatePassword(scope, password)) return false
        return data.write(record(NewTaskPassword(scope, password)))
    }
    fun enroll(items: List<NewTaskPassword>, password: String, share: Boolean): String? {
        validation(items, share)?.let { return it }
        val app = items.first().scope.app
        if (app.isEmpty()) return "Choose an app for these passwords."
        if (items.map { it.scope }.toSet() != TaskScope.appTasks(app).toSet() || items.any { has(it.scope) }) return "Existing app passwords cannot be replaced through setup."
        if (!check(TaskScope(TaskKind.ENROLL_APP), password)) return "Wrong new-app setup password, or this task is locked out."
        if (!share && items.any { !isSeparatePassword(it.scope, it.password) }) return "Generate separate app passwords, or explicitly permit chosen passwords and possible sharing."
        val changes = mutableMapOf("task.scopes" to (scopes() + items.map { it.scope }).joinToString("\n") { it.id })
        for (item in items) {
            if (Thread.currentThread().isInterrupted) return "Setup cancelled."
            changes.putAll(record(item))
        }
        return if (data.write(changes)) null else "Could not save app passwords."
    }
    fun unlock(app: String, minutes: Int, configuredMax: Int, password: String): Boolean {
        if (minutes !in 1..minOf(configuredMax, limit(app))) return false
        val scope = TaskScope(TaskKind.APP_UNLOCK, app)
        if (!check(scope, password)) return false
        return data.write(mapOf(prefix(scope) + "until" to (now() + minutes * 60_000L).toString()))
    }
    fun setLimit(app: String, minutes: Int, password: String): Boolean {
        if (minutes !in 1..10080 || !has(TaskScope(TaskKind.APP_UNLOCK, app))) return false
        if (!check(TaskScope(TaskKind.APP_LIMIT, app), password)) return false
        val key = prefix(TaskScope(TaskKind.APP_UNLOCK, app))
        return data.write(mapOf(key + "limit" to minutes.toString(), key + "until" to minOf(until(app), now() + minutes * 60_000L).toString()))
    }
    fun lock(app: String) = data.write(mapOf(prefix(TaskScope(TaskKind.APP_UNLOCK, app)) + "until" to "0"))
    fun lockAll() = data.write(scopes().filter { it.kind == TaskKind.APP_UNLOCK }
        .associate { prefix(it) + "until" to "0" })
    fun capAll(configuredMax: Int) {
        data.write(scopes().filter { it.kind == TaskKind.APP_UNLOCK }.associate {
            prefix(it) + "until" to minOf(until(it.app), now() + minOf(configuredMax, limit(it.app)) * 60_000L).toString()
        })
    }
}

internal object StorePasswordRules {
    fun valid(password: String) = password.length >= 8 && password.any { it.isLetter() } && password.any { it.isDigit() }
}
