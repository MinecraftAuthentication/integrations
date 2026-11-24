package me.minecraftauth.lib

/**
 * Base class for every error raised by the client.
 *
 * @property status the HTTP status the API answered with, or `null` when no response was received
 */
open class MinecraftAuthException(
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The request was rejected because of how the integration is set up: a malformed parameter (400),
 * missing credentials (401) or wrong or insufficient credentials (403). Retrying won't help; the
 * server owner needs to fix their configuration.
 */
class ConfigurationException(message: String, status: Int) : MinecraftAuthException(message, status)

/**
 * The answer is temporarily unavailable: the MinecraftAuth bot isn't in a Discord server (409),
 * too many pending access requests or rate limited (429, see [RateLimitedException]), an upstream platform failed
 * (502), a server error (5xx),
 * or the API couldn't be reached at all.
 */
open class UnavailableException(message: String, status: Int? = null, cause: Throwable? = null) :
    MinecraftAuthException(message, status, cause)

/**
 * The API is rate limiting this client (429), and waiting it out would take longer than
 * [MinecraftAuthConfig.maxRateLimitWaitMillis]. Shorter waits are retried automatically. Until [retryAfterMillis] has
 * passed, the client fails calls with this exception without sending them.
 *
 * @property retryAfterMillis how long to wait before trying again
 */
class RateLimitedException(message: String, val retryAfterMillis: Long) : UnavailableException(message, 429)

/** The requested resource doesn't exist or is hidden by the player's privacy settings (404). */
class NotFoundException(message: String) : MinecraftAuthException(message, 404)

/** A grant token failed verification. */
class InvalidTokenException(message: String, cause: Throwable? = null) : MinecraftAuthException(message, null, cause)
