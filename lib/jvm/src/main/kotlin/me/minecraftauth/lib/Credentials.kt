package me.minecraftauth.lib

import java.nio.charset.StandardCharsets
import java.util.Base64

/** How requests to the MinecraftAuth.me API are authenticated. */
sealed class Credentials {

    /** The value of the `Authorization` header, or `null` to send none. */
    abstract fun authorizationHeader(): String?

    /** Anonymous access. Only [MinecraftAuth.lookup] works without credentials. */
    object None : Credentials() {
        override fun authorizationHeader(): String? = null
        override fun toString(): String = "Credentials.None"
    }

    /**
     * A creator's server token from https://minecraftauth.me/developers, sent as `Bearer`.
     * Membership checks answer whether a player follows, subscribes to or supports the token's owner.
     */
    class ServerToken(private val token: String) : Credentials() {
        init {
            require(token.isNotBlank()) { "Server token must not be blank" }
        }

        override fun authorizationHeader(): String = "Bearer $token"
        override fun toString(): String = "Credentials.ServerToken(***)"
    }

    /** The legacy `uid:token` form of a server token, sent as `Basic`. */
    class ServerTokenBasic(private val uid: String, private val token: String) : Credentials() {
        override fun authorizationHeader(): String = basic(uid, token)
        override fun toString(): String = "Credentials.ServerTokenBasic($uid, ***)"
    }

    /**
     * Application credentials. Required for access (grant) requests. Membership checks made with them
     * run against the application owner. Never ship the secret inside something distributed to players.
     */
    class Application(val applicationId: String, private val secret: String) : Credentials() {
        init {
            require(applicationId.isNotBlank()) { "Application ID must not be blank" }
            require(secret.isNotBlank()) { "Application secret must not be blank" }
        }

        override fun authorizationHeader(): String = basic(applicationId, secret)
        override fun toString(): String = "Credentials.Application($applicationId, ***)"
    }

    internal companion object {
        fun basic(user: String, password: String): String =
            "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(StandardCharsets.UTF_8))
    }
}
