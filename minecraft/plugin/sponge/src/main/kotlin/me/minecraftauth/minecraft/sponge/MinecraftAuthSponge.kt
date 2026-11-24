package me.minecraftauth.minecraft.sponge

import com.google.inject.Inject
import me.minecraftauth.minecraft.CommandSource
import me.minecraftauth.minecraft.MinecraftAuthCommand
import me.minecraftauth.minecraft.MinecraftAuthService
import me.minecraftauth.minecraft.PlatformLogger
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.apache.logging.log4j.Logger
import org.spongepowered.api.command.Command
import org.spongepowered.api.command.CommandResult
import org.spongepowered.api.command.parameter.CommandContext
import org.spongepowered.api.command.parameter.Parameter
import org.spongepowered.api.config.ConfigDir
import org.spongepowered.api.event.Listener
import org.spongepowered.api.event.Order
import org.spongepowered.api.event.lifecycle.ConstructPluginEvent
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent
import org.spongepowered.api.event.network.ServerSideConnectionEvent
import org.spongepowered.plugin.PluginContainer
import org.spongepowered.plugin.builtin.jvm.Plugin
import java.nio.file.Path

@Plugin("minecraftauth")
class MinecraftAuthSponge @Inject constructor(
    private val container: PluginContainer,
    private val logger: Logger,
    @param:ConfigDir(sharedRoot = false) private val configDir: Path,
) {
    /** Set in [onConstruct]; tests swap in their own. */
    internal lateinit var service: MinecraftAuthService

    @Listener
    fun onConstruct(event: ConstructPluginEvent) {
        service = MinecraftAuthService(configDir.toFile(), object : PlatformLogger {
            override fun info(message: String) = logger.info(message)
            override fun warn(message: String) = logger.warn(message)
            override fun error(message: String, throwable: Throwable?) = logger.error(message, throwable)
        }, "Sponge")
    }

    /**
     * Fired off the main thread while the player authenticates. Sponge has no "operator" concept here, so admins
     * get in through the `bypass` list rather than `bypass-admins`.
     */
    @Listener(order = Order.LATE)
    fun onAuth(event: ServerSideConnectionEvent.Auth) {
        if (event.isCancelled) return
        val profile = event.profile()
        val name = profile.name().orElse(profile.uniqueId().toString())
        val result = service.check(profile.uniqueId(), name, false)
        if (result.denied) {
            event.setCancelled(true)
            event.setMessage(text(result.message))
        }
    }

    @Listener
    fun onRegisterCommands(event: RegisterCommandEvent<Command.Parameterized>) {
        val action = Parameter.string().key("action").optional().build()
        val command = Command.builder()
            .shortDescription(Component.text("MinecraftAuth status and reload"))
            .addParameter(action)
            .executor { context: CommandContext ->
                val source = context.cause().audience()
                MinecraftAuthCommand.execute(service, object : CommandSource {
                    override fun hasPermission(permission: String) = context.hasPermission(permission)
                    override fun sendMessage(message: String) = source.sendMessage(text(message))
                }, listOfNotNull(context.one(action).orElse(null)))
                CommandResult.success()
            }
            .build()
        event.register(container, command, MinecraftAuthCommand.NAME, *MinecraftAuthCommand.ALIASES.toTypedArray())
    }

    private fun text(legacy: String?): Component = LegacyComponentSerializer.legacySection().deserialize(legacy ?: "")
}
