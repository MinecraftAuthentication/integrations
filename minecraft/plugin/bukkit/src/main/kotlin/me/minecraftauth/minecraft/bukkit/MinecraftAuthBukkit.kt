package me.minecraftauth.minecraft.bukkit

import me.minecraftauth.minecraft.CommandSource
import me.minecraftauth.minecraft.MinecraftAuthCommand
import me.minecraftauth.minecraft.MinecraftAuthService
import me.minecraftauth.minecraft.PlatformLogger
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.entity.Player
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerLoginEvent
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.java.JavaPluginLoader
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/** Spigot / Paper / Folia plugin. */
class MinecraftAuthBukkit : JavaPlugin, Listener {

    constructor() : super()

    /** Bukkit's constructor for tests, outside a plugin class loader. */
    internal constructor(loader: JavaPluginLoader, description: PluginDescriptionFile, dataFolder: File, file: File) :
        super(loader, description, dataFolder, file)

    /** Set in [onEnable]; tests swap in their own. */
    internal var service: MinecraftAuthService? = null

    override fun onEnable() {
        val pluginLogger = logger
        service = MinecraftAuthService(dataFolder, object : PlatformLogger {
            override fun info(message: String) = pluginLogger.info(message)
            override fun warn(message: String) = pluginLogger.warning(message)
            override fun error(message: String, throwable: Throwable?) = pluginLogger.log(Level.SEVERE, message, throwable)
        }, if (isPaper()) "Paper" else "Bukkit")
        server.pluginManager.registerEvents(this, this)
    }

    override fun onDisable() {
        service = null
        pendingDenials.clear()
    }

    /**
     * Denials waiting for [onLogin], by player. Bukkit has no Player (and so no permissions) yet at pre-login,
     * so a denied player is only kicked once [onLogin] has checked the bypass permission.
     */
    private val pendingDenials = ConcurrentHashMap<UUID, PendingDenial>()

    private class PendingDenial(val message: String, val reason: String?, val createdAt: Long = System.currentTimeMillis())

    /** Runs off the main thread, so blocking on MinecraftAuth.me here doesn't lag the server. */
    @EventHandler(priority = EventPriority.HIGH)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        val service = service ?: return
        val isOp = server.operators.any { it.uniqueId == event.uniqueId }
        // Without admin bypass the permission doesn't matter, so deny right away
        val deferDenial = service.settings.bypassAdmins
        val result = service.check(event.uniqueId, event.name, isOp, logDenial = !deferDenial)
        if (!result.denied) return
        if (deferDenial) {
            purgeStaleDenials()
            pendingDenials[event.uniqueId] = PendingDenial(result.message ?: "", result.reason)
        } else {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, result.message ?: "")
        }
    }

    /** Another plugin may still deny the login after us; then [onLogin] never runs for this attempt. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPreLoginResult(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) pendingDenials.remove(event.uniqueId)
    }

    /**
     * Applies a denial from [onPreLogin] unless the player may bypass. Permissions plugins (LuckPerms, ...) have
     * set the player up by now, so [Player.hasPermission] works. Nothing here touches the network.
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onLogin(event: PlayerLoginEvent) {
        val player = event.player
        val denial = pendingDenials.remove(player.uniqueId) ?: return
        if (event.result != PlayerLoginEvent.Result.ALLOWED) return // already kicked (ban, whitelist, full, ...)
        if (player.isOp || player.hasPermission(MinecraftAuthService.BYPASS_PERMISSION)) {
            logger.info("${player.name} has ${MinecraftAuthService.BYPASS_PERMISSION}, letting them in without checks")
            return
        }
        logger.info("Denying ${player.name}" + (denial.reason?.let { ": $it" } ?: ""))
        event.disallow(PlayerLoginEvent.Result.KICK_WHITELIST, denial.message)
    }

    /** Drops denials whose login never arrived (the player disconnected in between). */
    private fun purgeStaleDenials() {
        val cutoff = System.currentTimeMillis() - 60_000
        pendingDenials.values.removeIf { it.createdAt < cutoff }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val service = service ?: return true
        MinecraftAuthCommand.execute(service, object : CommandSource {
            override fun hasPermission(permission: String) = sender.hasPermission(permission)
            override fun sendMessage(message: String) = message.split('\n').forEach(sender::sendMessage)
        }, args.toList())
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
        if (args.size == 1 && sender.hasPermission(MinecraftAuthService.RELOAD_PERMISSION) && "reload".startsWith(args[0].lowercase())) {
            listOf("reload")
        } else {
            emptyList()
        }

    private fun isPaper(): Boolean = try {
        Class.forName("io.papermc.paper.configuration.Configuration")
        true
    } catch (e: ClassNotFoundException) {
        try {
            Class.forName("com.destroystokyo.paper.PaperConfig")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}
