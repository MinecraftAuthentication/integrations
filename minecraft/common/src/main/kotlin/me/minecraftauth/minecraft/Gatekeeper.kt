package me.minecraftauth.minecraft

import me.minecraftauth.lib.ConfigurationException
import me.minecraftauth.lib.MinecraftAuthException
import java.util.UUID

/**
 * Decides whether a player may join a realm (the server, or a backend server behind a proxy).
 *
 * The player is allowed when any configured condition holds. Conditions are tried in order and evaluation
 * stops at the first one that passes, so put the cheapest or most common one first.
 */
class Gatekeeper(
    private val settings: Settings,
    private val conditions: Conditions,
    private val logger: PlatformLogger,
) {
    private val rateLimited = RateLimitedLog(logger)
    private val enabled = settings.serverToken != null
    private val realms: Map<String?, RealmSettings>

    init {
        val all = LinkedHashMap<String?, RealmSettings>()
        all[null] = settings.global
        all.putAll(settings.servers)
        realms = all.mapValues { (name, realm) -> realm.copy(conditions = realm.conditions.filter { validate(name, it) }) }

        if (!enabled) {
            if (realms.values.any { it.conditions.isNotEmpty() }) {
                logger.warn("No server token is set in ${Settings.FILE_NAME} (api.server-token), so no conditions are checked. " +
                    "Get one at https://minecraftauth.me/developers")
            }
        } else {
            val count = realms.values.sumOf { it.conditions.size }
            val serverRealms = realms.keys.count { it != null && realms[it]!!.conditions.isNotEmpty() }
            logger.info("Gatekeeper is checking $count condition${if (count == 1) "" else "s"}" +
                if (serverRealms > 0) " across the server and $serverRealms backend server${if (serverRealms == 1) "" else "s"}" else "")
        }
    }

    private fun validate(realm: String?, condition: String): Boolean = try {
        conditions.validate(condition)
        true
    } catch (e: Exception) {
        logger.error("Ignoring invalid condition '$condition'${realm?.let { " for server '$it'" } ?: ""}: ${e.message}")
        false
    }

    /**
     * Checks [player] against the realm for [server] (`null` = the server itself / proxy login).
     * Blocks on network I/O: call from an async login event or a network thread, never the main thread.
     *
     * With [logDenial] `false` a denial isn't logged, for a platform that may still let the player in later
     * (Bukkit checks the bypass permission once the player exists); it logs [GatekeeperResult.reason] itself.
     */
    @JvmOverloads
    fun check(player: UUID, name: String, isAdmin: Boolean, server: String? = null, logDenial: Boolean = true): GatekeeperResult {
        val realm = realms[server] ?: return GatekeeperResult.NOT_ENABLED
        if (!enabled || realm.conditions.isEmpty()) return GatekeeperResult.NOT_ENABLED
        val where = server?.let { " to $it" } ?: ""

        if (isAdmin && settings.bypassAdmins) {
            logger.info("$name is an admin, letting them in$where without checks")
            return GatekeeperResult.BYPASSED
        }
        if (realm.bypass.any { it.equals(player.toString(), true) || it.equals(name, true) || it.replace("-", "").equals(player.toString().replace("-", ""), true) }) {
            logger.info("$name is on the bypass list, letting them in$where")
            return GatekeeperResult.BYPASSED
        }

        var failure: MinecraftAuthException? = null
        for (condition in realm.conditions) {
            try {
                if (conditions.evaluate(condition, player)) return GatekeeperResult.ALLOWED
            } catch (e: ConfigurationException) {
                failure = failure ?: e
                rateLimited.warn("config:${e.status}", "MinecraftAuth.me rejected a check because of a configuration problem " +
                    "(is api.server-token correct, and is the bot in your Discord server?): ${e.message}")
            } catch (e: MinecraftAuthException) {
                failure = failure ?: e
                rateLimited.warn("unavailable", "Couldn't check '$condition' for $name: ${e.message}")
            } catch (e: Exception) {
                failure = failure ?: MinecraftAuthException(e.message ?: e.toString(), null, e)
                logger.error("Error evaluating '$condition' for $name", e)
            }
        }

        if (failure == null) {
            return denied(name, where, realm.deniedMessage, "none of the conditions are met", logDenial)
        }
        if (failure !is ConfigurationException && settings.onApiError == ApiErrorPolicy.ALLOW) {
            logger.info("Letting $name in$where because MinecraftAuth.me couldn't be reached and on-api-error is 'allow'")
            return GatekeeperResult.ALLOWED
        }
        return denied(name, where, realm.errorMessage, "their accounts couldn't be checked", logDenial)
    }

    private fun denied(name: String, where: String, message: String, reason: String, log: Boolean): GatekeeperResult {
        if (log) logger.info("Denying $name$where: $reason")
        return GatekeeperResult.denied(message, reason)
    }
}
