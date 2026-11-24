package me.minecraftauth.lib.grant

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.minecraftauth.lib.InvalidTokenException
import me.minecraftauth.lib.Platform
import me.minecraftauth.lib.internal.Http
import me.minecraftauth.lib.parseUuid
import java.nio.charset.StandardCharsets
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import java.util.UUID

/** An account included in a grant token. [name] and [avatar] may be `null`. */
data class GrantAccount(val identifier: String, val name: String?, val avatar: String?)

/** The verified claims of a grant token. */
data class GrantClaims(
    /** The player's MinecraftAuth.me identity ID. Stable; use it as your user key. */
    val subject: String,
    val issuer: String,
    /** Epoch seconds when the player approved. */
    val issuedAt: Long,
    /** Epoch seconds when the token expires. Always set on a verified token; [GrantTokenVerifier.decodeUnverified] may leave it `null`. */
    val expiresAt: Long?,
    /** The `aud` claim values: the ID of the application the token was issued to. */
    val audience: List<String>,
    val accounts: Map<Platform, GrantAccount>,
) {
    val minecraft: GrantAccount? get() = accounts[Platform.MINECRAFT]
    val minecraftUuid: UUID? get() = minecraft?.identifier?.let(::parseUuid)
}

/**
 * Verifies grant tokens: JWTs signed with RS512 that MinecraftAuth.me hands back when a player approves
 * an access request.
 *
 * Tokens from the polling flow arrived over an authenticated channel. Tokens from the redirect flow came
 * through the player's browser and **must** be verified.
 *
 * Every token must carry an `exp` claim and not have expired.
 *
 * Construct it with a [KeySource], usually `KeySource(client)`, so the signing key is fetched once, cached, and fetched
 * again (at most once a minute) when a signature doesn't match because MinecraftAuth.me rotated its key. The
 * constructor that takes a `() -> PublicKey` calls that function on every [verify] and never refreshes by itself.
 *
 * @param audience your application ID. If set, the token's `aud` claim must contain it, so a token issued to
 *                 another application is rejected. Set it whenever tokens reach you through a browser.
 * @param maxAgeSeconds if set, also reject tokens whose `iat` is older than this
 */
class GrantTokenVerifier private constructor(
    private val currentKey: () -> PublicKey,
    private val refreshKey: () -> Boolean,
    private val issuer: String,
    private val audience: String?,
    private val maxAgeSeconds: Long?,
    private val clockSkewSeconds: Long,
    private val nowSeconds: () -> Long,
) {

    /** Verifies with the key from [keys], refreshing it once when a signature doesn't match. */
    @JvmOverloads
    constructor(
        keys: KeySource,
        issuer: String = ISSUER,
        audience: String? = null,
        maxAgeSeconds: Long? = null,
        clockSkewSeconds: Long = 60,
        nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    ) : this(keys::key, keys::refresh, issuer, audience, maxAgeSeconds, clockSkewSeconds, nowSeconds)

    /** Verifies with whatever key [publicKey] returns, calling it on every [verify]. */
    @JvmOverloads
    constructor(
        publicKey: () -> PublicKey,
        issuer: String = ISSUER,
        audience: String? = null,
        maxAgeSeconds: Long? = null,
        clockSkewSeconds: Long = 60,
        nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    ) : this(publicKey, { false }, issuer, audience, maxAgeSeconds, clockSkewSeconds, nowSeconds)

    /**
     * @throws InvalidTokenException if the token fails verification
     * @throws me.minecraftauth.lib.MinecraftAuthException if the signing key had to be fetched and couldn't be
     */
    fun verify(token: String): GrantClaims {
        val parts = token.trim().split('.')
        if (parts.size != 3) throw InvalidTokenException("Not a JWT")
        val header = decodeObject(parts[0], "header")
        val alg = header["alg"]?.jsonPrimitive?.contentOrNull
        if (alg != "RS512") throw InvalidTokenException("Unexpected token algorithm '$alg'")

        val signature = try {
            Base64.getUrlDecoder().decode(parts[2])
        } catch (e: IllegalArgumentException) {
            throw InvalidTokenException("Malformed token signature", e)
        }
        val signingInput = (parts[0] + "." + parts[1]).toByteArray(StandardCharsets.US_ASCII)
        // On a mismatch, the key may have rotated: retry once if refreshing fetched a different one.
        val valid = signatureMatches(currentKey(), signingInput, signature) ||
            (refreshKey() && signatureMatches(currentKey(), signingInput, signature))
        if (!valid) throw InvalidTokenException("Token signature is invalid")

        val payload = decodeObject(parts[1], "payload")
        val claims = parseClaims(payload)
        val now = nowSeconds()
        if (claims.issuer != issuer) throw InvalidTokenException("Unexpected token issuer '${claims.issuer}'")
        if (claims.issuedAt > now + clockSkewSeconds) throw InvalidTokenException("Token was issued in the future")
        if (maxAgeSeconds != null && now - claims.issuedAt > maxAgeSeconds + clockSkewSeconds) {
            throw InvalidTokenException("Token is too old")
        }
        val expiresAt = claims.expiresAt ?: throw InvalidTokenException("Token has no exp")
        if (now > expiresAt + clockSkewSeconds) throw InvalidTokenException("Token has expired")
        if (audience != null && audience !in claims.audience) {
            throw InvalidTokenException("Token was issued for a different application")
        }
        return claims
    }

    private fun signatureMatches(key: PublicKey, signingInput: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance("SHA512withRSA").run {
            initVerify(key)
            update(signingInput)
            verify(signature)
        }
    } catch (e: Exception) {
        throw InvalidTokenException("Couldn't verify token signature", e)
    }

    companion object {
        const val ISSUER: String = "MinecraftAuth.me"

        /** Reads a token's claims without verifying anything. Only for tokens received over an authenticated channel. */
        @JvmStatic
        fun decodeUnverified(token: String): GrantClaims {
            val parts = token.trim().split('.')
            if (parts.size != 3) throw InvalidTokenException("Not a JWT")
            return parseClaims(decodeObject(parts[1], "payload"))
        }

        private fun decodeObject(part: String, what: String): JsonObject = try {
            val text = Base64.getUrlDecoder().decode(part).toString(StandardCharsets.UTF_8)
            Http.json.parseToJsonElement(text) as JsonObject
        } catch (e: Exception) {
            throw InvalidTokenException("Malformed token $what", e)
        }

        private fun parseClaims(payload: JsonObject): GrantClaims {
            val sub = payload["sub"]?.jsonPrimitive?.contentOrNull ?: throw InvalidTokenException("Token has no subject")
            val iat = payload["iat"]?.jsonPrimitive?.longOrNull ?: throw InvalidTokenException("Token has no iat")
            val aud = when (val value = payload["aud"]) {
                is JsonPrimitive -> listOfNotNull(value.contentOrNull)
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                else -> emptyList()
            }
            val accounts = LinkedHashMap<Platform, GrantAccount>()
            for (platform in Platform.entries) {
                val obj = payload[platform.id] as? JsonObject ?: continue
                val identifier = obj["identifier"]?.jsonPrimitive?.contentOrNull ?: continue
                accounts[platform] = GrantAccount(
                    identifier,
                    (obj["name"] as? JsonPrimitive)?.contentOrNull,
                    (obj["avatar"] as? JsonPrimitive)?.contentOrNull,
                )
            }
            return GrantClaims(
                subject = sub,
                issuer = payload["iss"]?.jsonPrimitive?.contentOrNull ?: "",
                issuedAt = iat,
                expiresAt = payload["exp"]?.jsonPrimitive?.longOrNull,
                audience = aud,
                accounts = accounts,
            )
        }
    }
}
