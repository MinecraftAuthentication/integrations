package me.minecraftauth.minecraft.bungee

import me.minecraftauth.minecraft.ApiErrorPolicy
import me.minecraftauth.minecraft.CommandSource
import me.minecraftauth.minecraft.GatekeeperResult
import me.minecraftauth.minecraft.MinecraftAuthCommand
import me.minecraftauth.minecraft.MinecraftAuthService
import me.minecraftauth.minecraft.PlatformLogger
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.event.LoginEvent
import net.md_5.bungee.api.event.ServerConnectEvent
import net.md_5.bungee.api.plugin.Command
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.plugin.PluginDescription
import net.md_5.bungee.api.plugin.TabExecutor
import net.md_5.bungee.event.EventHandler
import net.md_5.bungee.event.EventPriority
import java.util.logging.Level

class MinecraftAuthBungee : Plugin, Listener {

    constructor() : super()

    /** BungeeCord's constructor for tests, outside a plugin class loader. */
    internal constructor(proxy: ProxyServer, description: PluginDescription) : super(proxy, description)

    /** Set in [onEnable]; tests swap in their own. */
    internal lateinit var service: MinecraftAuthService

    override fun onEnable() {
        val pluginLogger = logger
        service = MinecraftAuthService(dataFolder, object : PlatformLogger {
            override fun info(message: String) = pluginLogger.info(message)
            override fun warn(message: String) = pluginLogger.warning(message)
            override fun error(message: String, throwable: Throwable?) = pluginLogger.log(Level.SEVERE, message, throwable)
        }, "BungeeCord", proxy = true)
        proxy.pluginManager.registerListener(this, this)
        proxy.pluginManager.registerCommand(this, McAuthCommand())
    }

    /** Proxy-wide conditions. The check runs on the scheduler while the login waits on an intent. */
    @EventHandler(priority = EventPriority.HIGH)
    fun onLogin(event: LoginEvent) {
        if (event.isCancelled) return
        val connection = event.connection
        val uuid = connection.uniqueId ?: return // offline-mode proxies have no real UUID yet
        event.registerIntent(this)
        proxy.scheduler.runAsync(this) {
            try {
                val result = check(connection.name) { service.check(uuid, connection.name, isAdmin(connection.name)) }
                if (result.denied) {
                    event.isCancelled = true
                    event.setCancelReason(*TextComponent.fromLegacyText(result.message ?: ""))
                }
            } finally {
                event.completeIntent(this)
            }
        }
    }

    /**
     * Runs [check], turning an unexpected error into what `on-api-error` asks for: a denial with the realm's
     * `messages.error` when it's `deny`, otherwise letting the player through.
     */
    private inline fun check(name: String, server: String? = null, check: () -> GatekeeperResult): GatekeeperResult = try {
        check()
    } catch (e: Exception) {
        logger.log(Level.SEVERE, "MinecraftAuth check failed for $name${server?.let { " to $it" } ?: ""}", e)
        val settings = service.settings
        if (settings.onApiError == ApiErrorPolicy.DENY) {
            GatekeeperResult.denied((server?.let { settings.servers[it] } ?: settings.global).errorMessage, "their accounts couldn't be checked")
        } else {
            GatekeeperResult.ALLOWED
        }
    }

    /**
     * Conditions for a specific backend server. BungeeCord can't defer this event, so the check blocks the
     * connecting thread; answers are cached (api.cache-seconds) so repeat switches are instant.
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onServerConnect(event: ServerConnectEvent) {
        if (event.isCancelled) return
        val player = event.player
        val server = event.target.name
        val result = check(player.name, server) {
            service.check(player.uniqueId, player.name, player.hasPermission(MinecraftAuthService.BYPASS_PERMISSION), server)
        }
        if (result.denied) {
            event.isCancelled = true
            val message = TextComponent.fromLegacyText(result.message ?: "")
            if (player.server == null) player.disconnect(*message) else player.sendMessage(*message)
        }
    }

    /** At login the player has no permissions yet, so look at the groups BungeeCord's config gives that name. */
    private fun isAdmin(name: String): Boolean {
        val config = proxy.configurationAdapter
        return config.getGroups(name).any { group -> MinecraftAuthService.BYPASS_PERMISSION in config.getPermissions(group) || "*" in config.getPermissions(group) }
    }

    private inner class McAuthCommand : Command(MinecraftAuthCommand.NAME, null, *MinecraftAuthCommand.ALIASES.toTypedArray()), TabExecutor {
        override fun execute(sender: CommandSender, args: Array<out String>) {
            MinecraftAuthCommand.execute(service, object : CommandSource {
                override fun hasPermission(permission: String) = sender.hasPermission(permission)
                override fun sendMessage(message: String) = sender.sendMessage(*TextComponent.fromLegacyText(message))
            }, args.toList())
        }

        override fun onTabComplete(sender: CommandSender, args: Array<out String>): Iterable<String> =
            if (args.size == 1 && sender.hasPermission(MinecraftAuthService.RELOAD_PERMISSION) && "reload".startsWith(args[0].lowercase())) {
                listOf("reload")
            } else {
                emptyList()
            }
    }
}
