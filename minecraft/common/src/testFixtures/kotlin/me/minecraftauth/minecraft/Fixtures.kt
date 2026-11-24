package me.minecraftauth.minecraft

import me.minecraftauth.lib.LookupResult
import me.minecraftauth.lib.Platform
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Notch's UUID, a stable player for tests. */
@JvmField val NOTCH: UUID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")

/** Answers membership questions from a script of facts and records every call (e.g. `following:twitch`). */
class FakeMembership : Membership {
    val calls: MutableList<String> = CopyOnWriteArrayList()
    var following = setOf<Platform>()
    var subscribed = mapOf<Platform, Set<String?>>()
    var servers = setOf<String>()
    /** Held roles, as "server:role" (server "null" for a role ID given alone). */
    var roles = setOf<String>()
    var linked: LookupResult? = null
    /** Thrown by every call. */
    var failure: RuntimeException? = null
    /** Thrown by the calls whose record (`following:twitch`, ...) is a key here; checked before [failure]. */
    val failures = mutableMapOf<String, RuntimeException>()

    private fun record(call: String) {
        calls += call
        (failures[call] ?: failure)?.let { throw it }
    }

    override fun following(player: UUID, platform: Platform): Boolean {
        record("following:$platform"); return platform in following
    }

    override fun subscribed(player: UUID, platform: Platform, data: String?): Boolean {
        record("subscribed:$platform:$data"); return subscribed[platform]?.contains(data) == true
    }

    override fun discordRole(player: UUID, role: String, serverId: String?): Boolean {
        record("role:$serverId:$role"); return "$serverId:$role" in roles
    }

    override fun discordPresent(player: UUID, serverId: String): Boolean {
        record("present:$serverId"); return serverId in servers
    }

    override fun lookup(player: UUID, include: Set<Platform>): LookupResult? {
        record("lookup:${include.joinToString()}"); return linked
    }
}

/** Keeps every message, prefixed with its level (`INFO `, `WARN `, `ERROR `). */
class RecordingLogger : PlatformLogger {
    val lines: MutableList<String> = CopyOnWriteArrayList()
    val errors: MutableList<Throwable?> = CopyOnWriteArrayList()

    override fun info(message: String) { lines += "INFO $message" }
    override fun warn(message: String) { lines += "WARN $message" }
    override fun error(message: String, throwable: Throwable?) { lines += "ERROR $message"; errors += throwable }

    fun warnings(): List<String> = lines.filter { it.startsWith("WARN ") }.map { it.removePrefix("WARN ") }
    fun infos(): List<String> = lines.filter { it.startsWith("INFO ") }.map { it.removePrefix("INFO ") }
    fun errors(): List<String> = lines.filter { it.startsWith("ERROR ") }.map { it.removePrefix("ERROR ") }
}

/**
 * A `MinecraftAuth.yml` with application credentials (and [serverToken], if not blank), `on-api-error: deny`, denied message `&cnope`, error message `&cbroken`
 * and one list item per condition. [bypass] replaces the bundled bypass list. [extra] is appended under `gatekeeper:`
 * (indent it by 2).
 */
fun testConfig(
    vararg conditions: String,
    extra: String = "",
    applicationId: String = "AppAppAppAppApp1",
    applicationSecret: String = "secret",
    serverToken: String = "",
    bypassAdmins: Boolean = true,
    onApiError: String = "deny",
    bypass: List<String> = emptyList(),
): String = buildString {
    appendLine("api:")
    appendLine("  application-id: \"$applicationId\"")
    appendLine("  application-secret: \"$applicationSecret\"")
    if (serverToken.isNotBlank()) appendLine("  server-token: \"$serverToken\"")
    appendLine("gatekeeper:")
    appendLine("  bypass: [" + bypass.joinToString { "\"$it\"" } + "]")
    appendLine("  bypass-admins: $bypassAdmins")
    appendLine("  on-api-error: $onApiError")
    appendLine("  messages:")
    appendLine("    denied: \"&cnope\"")
    appendLine("    error: \"&cbroken\"")
    appendLine(if (conditions.isEmpty()) "  conditions: []" else "  conditions:")
    conditions.forEach { appendLine("    - '" + it.replace("'", "''") + "'") }
    if (extra.isNotEmpty()) appendLine(extra)
}

/** Writes [yaml] to [dir] and starts a service that asks [membership] instead of the API. */
@JvmOverloads
fun testService(
    dir: File,
    yaml: String,
    membership: Membership = FakeMembership(),
    logger: PlatformLogger = RecordingLogger(),
    proxy: Boolean = false,
): MinecraftAuthService {
    File(dir, Settings.FILE_NAME).writeText(yaml)
    return MinecraftAuthService(dir, logger, "Test", proxy) { membership }
}
