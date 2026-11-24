package me.minecraftauth.minecraft.velocity

import com.google.inject.Inject
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import me.minecraftauth.minecraft.CommandSource
import me.minecraftauth.minecraft.MinecraftAuthCommand
import me.minecraftauth.minecraft.MinecraftAuthService
import me.minecraftauth.minecraft.PlatformLogger
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.slf4j.Logger
import java.nio.file.Path

class MinecraftAuthVelocity @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @param:DataDirectory private val dataDirectory: Path,
) {
    /** Set in [onProxyInitialize]; tests swap in their own. */
    internal lateinit var service: MinecraftAuthService

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        service = MinecraftAuthService(dataDirectory.toFile(), object : PlatformLogger {
            override fun info(message: String) = logger.info(message)
            override fun warn(message: String) = logger.warn(message)
            override fun error(message: String, throwable: Throwable?) = logger.error(message, throwable)
        }, "Velocity", proxy = true)

        val commands = proxy.commandManager
        commands.register(
            commands.metaBuilder(MinecraftAuthCommand.NAME).aliases(*MinecraftAuthCommand.ALIASES.toTypedArray()).plugin(this).build(),
            Command(),
        )
    }

    /** Checks the proxy-wide conditions. Runs as an async task so the network threads aren't blocked. */
    @Subscribe
    fun onLogin(event: LoginEvent): EventTask? {
        if (!event.result.isAllowed) return null
        return EventTask.async {
            val player = event.player
            val result = service.check(player.uniqueId, player.username, isAdmin(player))
            if (result.denied) event.result = ResultedEvent.ComponentResult.denied(text(result.message))
        }
    }

    /** Checks the conditions for the backend server the player is about to join. */
    @Subscribe
    fun onServerPreConnect(event: ServerPreConnectEvent): EventTask? {
        val target = event.result.server.orElse(null) ?: return null
        return EventTask.async {
            val player = event.player
            val result = service.check(player.uniqueId, player.username, isAdmin(player), target.serverInfo.name)
            if (result.denied) {
                event.result = ServerPreConnectEvent.ServerResult.denied()
                if (player.currentServer.isPresent) {
                    player.sendMessage(text(result.message))
                } else {
                    // initial connection: there's nowhere else to put them
                    player.disconnect(text(result.message))
                }
            }
        }
    }

    private fun isAdmin(player: Player) = player.hasPermission(MinecraftAuthService.BYPASS_PERMISSION)

    private fun text(legacy: String?): Component = LegacyComponentSerializer.legacySection().deserialize(legacy ?: "")

    private inner class Command : SimpleCommand {
        override fun execute(invocation: SimpleCommand.Invocation) {
            val source = invocation.source()
            MinecraftAuthCommand.execute(service, object : CommandSource {
                override fun hasPermission(permission: String) = source.hasPermission(permission)
                override fun sendMessage(message: String) = source.sendMessage(text(message))
            }, invocation.arguments().toList())
        }

        override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
            val args = invocation.arguments()
            return if (args.size <= 1 && invocation.source().hasPermission(MinecraftAuthService.RELOAD_PERMISSION) &&
                "reload".startsWith(args.firstOrNull()?.lowercase() ?: "")
            ) listOf("reload") else emptyList()
        }
    }
}
