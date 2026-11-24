package me.minecraftauth.lib

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.minecraftauth.lib.grant.PublicKeys
import me.minecraftauth.lib.internal.Http
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Configuration for [MinecraftAuth].
 *
 * @property userAgent required by the API. Name your integration and its version, e.g. `MyPlugin/1.4`.
 * @property credentials how to authenticate. Membership checks need application credentials.
 * @property baseUrl the API host; every path already includes `/api/v2`.
 */
data class MinecraftAuthConfig @JvmOverloads constructor(
    val userAgent: String,
    val credentials: Credentials = Credentials.None,
    val baseUrl: String = DEFAULT_BASE_URL,
    val connectTimeoutMillis: Int = 5_000,
    val readTimeoutMillis: Int = 10_000,
) {
    init {
        require(userAgent.isNotBlank()) { "A User-Agent naming your integration is required by the MinecraftAuth.me API" }
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://minecraftauth.me"
    }
}

/**
 * Client for the MinecraftAuth.me v2 API.
 *
 * Every call blocks on network I/O. Don't call them on a game's main thread; use the `…Async` variants,
 * which run on [executor] (a small shared daemon pool by default).
 *
 * Membership checks ([following], [subscribed], [discordPresent]) return `false` when the player isn't
 * linked or hides the account (HTTP 404). Other failures throw:
 * - [ConfigurationException] for 400/401/403, a mistake in the integration's setup
 * - [UnavailableException] for 409/429/5xx and network errors, a temporary problem
 */
class MinecraftAuth @JvmOverloads constructor(
    val config: MinecraftAuthConfig,
    private val executor: Executor = DEFAULT_EXECUTOR,
) {
    private val http = Http(config)

    /**
     * Looks up the accounts linked to the identity that owns [identifier] on [platform].
     *
     * @param include limit the result to these platforms (empty = all)
     * @return the visible accounts, or `null` if no identity has that account linked (or it's hidden from you)
     */
    @JvmOverloads
    fun lookup(platform: Platform, identifier: String, include: Set<Platform> = emptySet()): LookupResult? {
        val query = if (include.isEmpty()) emptyMap() else mapOf("include" to include.joinToString(",") { it.id })
        val response = http.get("/api/v2/lookup/${platform.id}/${Http.encodePath(identifier)}", query)
        if (response.status == 404) return null
        val json = response.expectJson()
        val accounts = json["accounts"]?.jsonObject ?: return LookupResult(emptyMap())
        val parsed = LinkedHashMap<Platform, LinkedAccount>()
        for ((key, value) in accounts) {
            val p = Platform.fromId(key) ?: continue
            val obj = value as? JsonObject ?: continue
            parsed[p] = LinkedAccount(
                identifier = obj["identifier"]?.jsonPrimitive?.contentOrNull,
                hint = obj["hint"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
        return LookupResult(parsed)
    }

    /** Looks up a player by Minecraft UUID. */
    @JvmOverloads
    fun lookup(minecraft: UUID, include: Set<Platform> = emptySet()): LookupResult? =
        lookup(Platform.MINECRAFT, minecraft.toString(), include)

    /**
     * Whether the player follows you: [Platform.TWITCH] means following your channel,
     * [Platform.GOOGLE] means subscribed to your YouTube channel.
     */
    fun following(minecraft: UUID, platform: Platform): Boolean {
        require(platform == Platform.TWITCH || platform == Platform.GOOGLE) { "following supports twitch and google, not $platform" }
        return membership("/api/v2/following", mapOf("minecraft" to minecraft.toString(), "platform" to platform.id))
    }

    /**
     * Whether the player subscribes to or supports you on [platform].
     *
     * [data] narrows the check:
     * - [Platform.DISCORD]: a role ID. **Required.** For a role name, use [discordRole] with the server ID.
     * - [Platform.TWITCH]: minimum sub tier `1`, `2` or `3`.
     * - [Platform.PATREON]: a tier title, matched ignoring case.
     * - [Platform.GOOGLE]: channel membership; `data` is ignored.
     */
    @JvmOverloads
    fun subscribed(minecraft: UUID, platform: Platform, data: String? = null): Boolean {
        require(platform != Platform.MINECRAFT) { "subscribed doesn't support minecraft" }
        val query = linkedMapOf("minecraft" to minecraft.toString(), "platform" to platform.id)
        if (!data.isNullOrBlank()) query["data"] = data
        return membership("/api/v2/subscribed", query)
    }

    /**
     * Whether the player's linked Discord account has [role] in a Discord server you moderate.
     *
     * [role] is a role ID, or a role name matched ignoring case. A name needs [serverId]: anyone can create a role
     * with the same name in a server of their own, so the API only looks names up in the server you name.
     */
    @JvmOverloads
    fun discordRole(minecraft: UUID, role: String, serverId: String? = null): Boolean {
        require(role.isNotBlank()) { "discordRole needs a role ID or name" }
        val query = linkedMapOf("minecraft" to minecraft.toString(), "platform" to Platform.DISCORD.id, "data" to role)
        if (!serverId.isNullOrBlank()) query["server"] = serverId
        return membership("/api/v2/subscribed", query)
    }

    /** Whether the player's linked Discord account is a member of the Discord server [serverId]. */
    fun discordPresent(minecraft: UUID, serverId: String): Boolean =
        membership("/api/v2/discord/present", mapOf("minecraft" to minecraft.toString(), "server" to serverId))

    /**
     * Starts an access request. Requires [Credentials.Application].
     *
     * @param scopes platforms to ask for; `minecraft` is always included
     * @param persist `false` for a one-off check that returns a token but stores nothing
     */
    @JvmOverloads
    fun createGrantRequest(scopes: Collection<Platform> = emptyList(), persist: Boolean = true): GrantRequest {
        val body = buildJsonObject {
            putJsonArray("scopes") { scopes.forEach { add(JsonPrimitive(it.id)) } }
            put("persist", persist)
        }
        val json = http.post("/api/v2/grant-requests", body.toString()).expectJson()
        return GrantRequest(
            id = json.string("id"),
            code = json.string("code"),
            url = json.string("url"),
            verificationUrl = json.string("verificationUrl"),
            scopes = json["scopes"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf(Platform.MINECRAFT.id),
            persist = json["persist"]?.jsonPrimitive?.boolean ?: persist,
            expiresAt = json["expiresAt"]!!.jsonPrimitive.long,
            interval = json["interval"]?.jsonPrimitive?.int ?: 5,
        )
    }

    /** Polls an access request started by [createGrantRequest]. Throws [NotFoundException] once it's gone. */
    fun getGrantRequest(id: String): GrantRequestStatus {
        val response = http.get("/api/v2/grant-requests/${Http.encodePath(id)}")
        if (response.status == 404) throw NotFoundException(response.errorMessage())
        val json = response.expectJson()
        val status = when (json.string("status").lowercase()) {
            "pending" -> GrantStatus.PENDING
            "accepted" -> GrantStatus.ACCEPTED
            "denied", "canceled", "cancelled" -> GrantStatus.DENIED
            else -> GrantStatus.EXPIRED
        }
        return GrantRequestStatus(status, json["expiresAt"]!!.jsonPrimitive.long, json["token"]?.jsonPrimitive?.contentOrNull)
    }

    /**
     * Blocks, polling [request] at its advertised interval, until the player answers or it expires.
     * Run this off the main thread.
     */
    fun awaitGrant(request: GrantRequest): GrantRequestStatus {
        val intervalMillis = request.interval.coerceAtLeast(1) * 1000L
        while (true) {
            val status = getGrantRequest(request.id)
            if (status.status.isFinal) return status
            if (System.currentTimeMillis() + intervalMillis > status.expiresAt) {
                return status.copy(status = GrantStatus.EXPIRED)
            }
            Thread.sleep(intervalMillis)
        }
    }

    /**
     * Downloads the RSA public key that signs grant tokens. To verify tokens, prefer
     * [me.minecraftauth.lib.grant.KeySource], which caches the key and fetches it again when it rotates.
     */
    fun fetchPublicKey(): PublicKey {
        val response = http.get("/rsa")
        if (response.status !in 200..299) response.throwForStatus()
        return PublicKeys.parsePem(response.body)
    }

    // ---- async variants ----

    @JvmOverloads
    fun lookupAsync(platform: Platform, identifier: String, include: Set<Platform> = emptySet()): CompletableFuture<LookupResult?> =
        async { lookup(platform, identifier, include) }

    fun followingAsync(minecraft: UUID, platform: Platform): CompletableFuture<Boolean> =
        async { following(minecraft, platform) }

    @JvmOverloads
    fun subscribedAsync(minecraft: UUID, platform: Platform, data: String? = null): CompletableFuture<Boolean> =
        async { subscribed(minecraft, platform, data) }

    @JvmOverloads
    fun discordRoleAsync(minecraft: UUID, role: String, serverId: String? = null): CompletableFuture<Boolean> =
        async { discordRole(minecraft, role, serverId) }

    fun discordPresentAsync(minecraft: UUID, serverId: String): CompletableFuture<Boolean> =
        async { discordPresent(minecraft, serverId) }

    @JvmOverloads
    fun createGrantRequestAsync(scopes: Collection<Platform> = emptyList(), persist: Boolean = true): CompletableFuture<GrantRequest> =
        async { createGrantRequest(scopes, persist) }

    fun awaitGrantAsync(request: GrantRequest): CompletableFuture<GrantRequestStatus> =
        async { awaitGrant(request) }

    private fun <T> async(block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync({ block() }, executor)

    private fun membership(path: String, query: Map<String, String>): Boolean {
        val response = http.get(path, query)
        if (response.status == 404) return false
        return response.expectJson()["result"]?.jsonPrimitive?.booleanOrNull
            ?: throw UnavailableException("MinecraftAuth.me returned a membership response without a result", response.status)
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: throw UnavailableException("MinecraftAuth.me response is missing '$key'")

    companion object {
        private val DEFAULT_EXECUTOR: ExecutorService by lazy {
            val counter = AtomicInteger()
            Executors.newCachedThreadPool(ThreadFactory { runnable ->
                Thread(runnable, "MinecraftAuth-" + counter.incrementAndGet()).apply { isDaemon = true }
            })
        }
    }
}
