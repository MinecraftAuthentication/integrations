package me.minecraftauth.lib.grant

import me.minecraftauth.lib.MinecraftAuth
import java.security.PublicKey

/**
 * Supplies the RSA key that signs grant tokens: fetched the first time it's needed, then cached.
 *
 * When a token's signature doesn't match, [GrantTokenVerifier] calls [refresh] in case MinecraftAuth.me rotated its
 * key. A refresh fetches again at most once per [refreshIntervalMillis], so a flood of forged tokens can't make it
 * hammer the API. Create one per application and share it; it's thread-safe.
 *
 * @param fetch downloads the key, usually [MinecraftAuth.fetchPublicKey]
 * @param refreshIntervalMillis the least time between two fetches made by [refresh]
 */
class KeySource @JvmOverloads constructor(
    private val fetch: () -> PublicKey,
    private val refreshIntervalMillis: Long = 60_000,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    /** Fetches the key from the API [client] talks to (`{baseUrl}/rsa`). */
    constructor(client: MinecraftAuth) : this(client::fetchPublicKey)

    private var key: PublicKey? = null
    private var fetchedAt: Long? = null

    /**
     * The cached key, fetched first if there is none yet.
     *
     * @throws me.minecraftauth.lib.MinecraftAuthException if it had to be fetched and couldn't be
     */
    @Synchronized
    fun key(): PublicKey = key ?: fetchLocked()

    /**
     * Fetches the key again, unless the last fetch was less than [refreshIntervalMillis] ago.
     * A failed fetch keeps the cached key.
     *
     * @return whether a different key was fetched
     */
    @Synchronized
    fun refresh(): Boolean {
        val last = fetchedAt
        if (last != null && clockMillis() - last < refreshIntervalMillis) return false
        val old = key
        val fresh = try {
            fetchLocked()
        } catch (e: Exception) {
            return false
        }
        return old != fresh
    }

    private fun fetchLocked(): PublicKey {
        fetchedAt = clockMillis()
        return fetch().also { key = it }
    }
}
