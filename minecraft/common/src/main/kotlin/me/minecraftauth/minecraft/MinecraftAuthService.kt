package me.minecraftauth.minecraft

import me.minecraftauth.lib.Credentials
import me.minecraftauth.lib.MinecraftAuth
import me.minecraftauth.lib.MinecraftAuthConfig
import java.io.File
import java.util.Properties
import java.util.UUID

/**
 * Everything a platform integration needs: loads `MinecraftAuth.yml`, talks to MinecraftAuth.me and runs the
 * gatekeeper. Platforms only translate their login events into [check] calls.
 *
 * @param platformName used in the User-Agent, e.g. `Paper`, `Velocity`, `NeoForge`
 * @param proxy whether this is a proxy (Velocity, BungeeCord), which adds per-backend-server options to the config
 * @param membershipFactory overrides how membership is checked (tests)
 */
class MinecraftAuthService @JvmOverloads constructor(
    private val dataDir: File,
    val logger: PlatformLogger,
    val platformName: String,
    val proxy: Boolean = false,
    private val membershipFactory: (Settings) -> Membership = { settings -> ApiMembership(createClient(settings, platformName)) },
) {
    @Volatile var settings: Settings = Settings.load(dataDir, proxy)
        private set

    @Volatile private var gatekeeper: Gatekeeper = build(settings)

    /** Re-reads `MinecraftAuth.yml` and clears cached answers. */
    fun reload() {
        val fresh = Settings.load(dataDir, proxy)
        gatekeeper = build(fresh)
        settings = fresh
        logger.info("Reloaded ${Settings.FILE_NAME}")
    }

    /**
     * Whether [player] may join [server] (`null` = this server, or the proxy itself).
     * Blocks on network I/O; call it from an async event or network thread. See [Gatekeeper.check] for [logDenial].
     */
    @JvmOverloads
    fun check(player: UUID, name: String, isAdmin: Boolean, server: String? = null, logDenial: Boolean = true): GatekeeperResult =
        gatekeeper.check(player, name, isAdmin, server, logDenial)

    private fun build(settings: Settings): Gatekeeper =
        Gatekeeper(settings, Conditions(membershipFactory(settings), settings.cacheSeconds), logger)

    companion object {
        /** The version of the Minecraft integrations, from the build. */
        @JvmStatic
        val VERSION: String by lazy {
            val props = Properties()
            MinecraftAuthService::class.java.getResourceAsStream("/minecraftauth-version.properties")?.use(props::load)
            props.getProperty("version", "dev")
        }

        /** The permission that lets a player skip the gatekeeper when `bypass-admins` is on. */
        const val BYPASS_PERMISSION = "minecraftauth.bypass"

        /** The permission for `/minecraftauth reload`. */
        const val RELOAD_PERMISSION = "minecraftauth.reload"

        @JvmStatic
        fun createClient(settings: Settings, platformName: String): MinecraftAuth = MinecraftAuth(
            MinecraftAuthConfig(
                userAgent = "MinecraftAuth-$platformName/$VERSION",
                credentials = settings.serverToken?.let { Credentials.ServerToken(it) } ?: Credentials.None,
            )
        )
    }
}

/** Someone running `/minecraftauth`. Messages are legacy text with `§` color codes. */
interface CommandSource {
    fun hasPermission(permission: String): Boolean
    fun sendMessage(message: String)
}

/** `/minecraftauth [reload]`, shared by every platform. */
object MinecraftAuthCommand {
    const val NAME = "minecraftauth"
    val ALIASES = listOf("mcauth")

    @JvmStatic
    fun execute(service: MinecraftAuthService, source: CommandSource, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "reload" -> {
                if (!source.hasPermission(MinecraftAuthService.RELOAD_PERMISSION)) {
                    source.sendMessage(Text.colorize("&cYou don't have permission to do that."))
                    return
                }
                try {
                    service.reload()
                    source.sendMessage(Text.colorize("&aMinecraftAuth configuration reloaded."))
                } catch (e: Exception) {
                    service.logger.error("Failed to reload ${Settings.FILE_NAME}", e)
                    source.sendMessage(Text.colorize("&cCouldn't reload ${Settings.FILE_NAME}: ${e.message}"))
                }
            }
            else -> {
                val settings = service.settings
                source.sendMessage(Text.colorize("&eMinecraftAuth &7v${MinecraftAuthService.VERSION} (${service.platformName}) &8- &fhttps://minecraftauth.me"))
                source.sendMessage(Text.colorize(
                    if (settings.serverToken == null) "&cNo server token configured; the gatekeeper is off."
                    else "&7Gatekeeper: &f${settings.global.conditions.size}&7 condition(s)" +
                        if (settings.servers.isEmpty()) "" else ", &f${settings.servers.size}&7 backend server rule set(s)"
                ))
                if (source.hasPermission(MinecraftAuthService.RELOAD_PERMISSION)) {
                    source.sendMessage(Text.colorize("&7/$NAME reload &8- &freload the configuration"))
                }
            }
        }
    }
}
