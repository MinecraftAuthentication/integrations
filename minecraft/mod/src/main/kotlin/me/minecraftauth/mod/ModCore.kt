package me.minecraftauth.mod

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import me.minecraftauth.minecraft.CommandSource
import me.minecraftauth.minecraft.MinecraftAuthCommand
import me.minecraftauth.minecraft.MinecraftAuthService
import me.minecraftauth.minecraft.PlatformLogger
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import org.apache.logging.log4j.LogManager
import java.nio.file.Path
import java.util.UUID
//? if <1.19 {
/*import net.minecraft.network.chat.TextComponent
*///?}

/** Loader-independent part of the mod. Each loader's entrypoint calls [init] and [registerCommands]. */
object ModCore {
    const val MOD_ID = "minecraftauth"

    private val logger = LogManager.getLogger("MinecraftAuth")

    @Volatile
    private var service: MinecraftAuthService? = null

    @JvmStatic
    fun init(configDir: Path, platformName: String) {
        service = MinecraftAuthService(configDir.toFile(), object : PlatformLogger {
            override fun info(message: String) = logger.info(message)
            override fun warn(message: String) = logger.warn(message)
            override fun error(message: String, throwable: Throwable?) = logger.error(message, throwable)
        }, platformName)
    }

    /**
     * Called from [me.minecraftauth.mod.mixin.PlayerListMixin] once vanilla has accepted a login.
     * Returns the kick message, or `null` to let the player in.
     *
     * Vanilla calls this on the server thread, so the first check for a player can stall a tick while
     * MinecraftAuth.me answers; repeat joins are served from the cache (api.cache-seconds).
     */
    @JvmStatic
    fun check(uuid: UUID, name: String, isAdmin: Boolean): Component? {
        val service = service ?: return null
        return try {
            val result = service.check(uuid, name, isAdmin)
            if (result.denied) text(result.message ?: "") else null
        } catch (e: Exception) {
            logger.error("MinecraftAuth check failed for $name", e)
            text("§cCouldn't verify your linked accounts, please try again.")
        }
    }

    @JvmStatic
    fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(command(MinecraftAuthCommand.NAME))
        MinecraftAuthCommand.ALIASES.forEach { dispatcher.register(command(it)) }
    }

    private fun command(name: String): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name)
            .executes { context -> run(context.source, emptyList()) }
            .then(Commands.literal("reload").executes { context -> run(context.source, listOf("reload")) })

    private fun run(stack: CommandSourceStack, args: List<String>): Int {
        val service = service ?: return 0
        MinecraftAuthCommand.execute(service, object : CommandSource {
            // vanilla has no permission nodes; reload needs op level 3 like other admin commands
            override fun hasPermission(permission: String): Boolean = isAdmin(stack)

            override fun sendMessage(message: String) {
                //? if >=1.20 {
                stack.sendSuccess({ text(message) }, false)
                //?} else {
                /*stack.sendSuccess(text(message), false)
                *///?}
            }
        }, args)
        return 1
    }

    private fun isAdmin(stack: CommandSourceStack): Boolean {
        //? if >=1.21.11 {
        /*return stack.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN)
        *///?} else {
        return stack.hasPermission(3)
        //?}
    }

    @JvmStatic
    fun text(message: String): Component {
        //? if >=1.19 {
        return Component.literal(message)
        //?} else {
        /*return TextComponent(message)
        *///?}
    }
}
