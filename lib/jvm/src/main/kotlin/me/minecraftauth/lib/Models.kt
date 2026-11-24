package me.minecraftauth.lib

import java.util.UUID

/**
 * An account linked to a MinecraftAuth.me identity.
 *
 * Either [identifier] is set (the account is public or visible to you), or [hint] is `true`
 * (the player shows that the platform is linked, but not which account).
 */
data class LinkedAccount(val identifier: String?, val hint: Boolean) {
    val isHint: Boolean get() = identifier == null
}

/**
 * The result of a lookup. A platform missing from [accounts] means unlinked, hidden, or not requested
 * through `include`. It never proves the player has no account there.
 */
data class LookupResult(val accounts: Map<Platform, LinkedAccount>) {
    operator fun get(platform: Platform): LinkedAccount? = accounts[platform]
    fun has(platform: Platform): Boolean = platform in accounts

    /** The Minecraft UUID, if visible. */
    val minecraft: UUID? get() = accounts[Platform.MINECRAFT]?.identifier?.let(::parseUuid)
}

/** A newly created access request. Show the player [url], or [code] together with [verificationUrl]. */
data class GrantRequest(
    val id: String,
    val code: String,
    val url: String,
    val verificationUrl: String,
    val scopes: List<String>,
    val persist: Boolean,
    /** Epoch milliseconds. */
    val expiresAt: Long,
    /** Seconds to wait between polls. */
    val interval: Int,
)

enum class GrantStatus {
    PENDING, ACCEPTED, DENIED, EXPIRED;

    val isFinal: Boolean get() = this != PENDING
}

data class GrantRequestStatus(
    val status: GrantStatus,
    /** Epoch milliseconds. */
    val expiresAt: Long,
    /** The signed grant token (JWT); present when [status] is [GrantStatus.ACCEPTED]. */
    val token: String?,
)

/** Parses a Minecraft UUID written with or without dashes, or returns `null`. */
fun parseUuid(value: String): UUID? {
    val raw = value.trim()
    return try {
        when (raw.length) {
            36 -> UUID.fromString(raw)
            32 -> UUID.fromString(
                raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12, 16) + "-" +
                    raw.substring(16, 20) + "-" + raw.substring(20)
            )
            else -> null
        }
    } catch (e: IllegalArgumentException) {
        null
    }
}
