package me.minecraftauth.minecraft.bukkit

import me.minecraftauth.lib.Platform
import me.minecraftauth.minecraft.FakeMembership
import me.minecraftauth.minecraft.MinecraftAuthService
import me.minecraftauth.minecraft.NOTCH
import me.minecraftauth.minecraft.testConfig
import me.minecraftauth.minecraft.testService
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerLoginEvent
import org.bukkit.plugin.PluginDescriptionFile
import org.bukkit.plugin.java.JavaPluginLoader
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drives the plugin's listeners with real Bukkit events; the server and players are mocks. */
class MinecraftAuthBukkitTest {

    @TempDir lateinit var dir: File

    private val operators = mutableSetOf<OfflinePlayer>()
    private val fake = FakeMembership()
    private lateinit var plugin: MinecraftAuthBukkit

    @BeforeEach
    fun setUp() {
        val server = mock(Server::class.java)
        `when`(server.logger).thenReturn(Logger.getLogger("MinecraftAuthBukkitTest"))
        `when`(server.operators).thenAnswer { operators.toSet() }
        plugin = MinecraftAuthBukkit(
            @Suppress("DEPRECATION") JavaPluginLoader(server),
            PluginDescriptionFile("MinecraftAuth", "test", MinecraftAuthBukkit::class.java.name),
            dir,
            File(dir, "MinecraftAuth.jar"),
        )
        configure(testConfig("TwitchFollower()"))
    }

    private fun configure(yaml: String) {
        plugin.service = testService(dir, yaml, fake)
    }

    /** Runs both pre-login handlers, as Bukkit would at HIGH and MONITOR. */
    private fun preLogin(uuid: UUID = NOTCH, name: String = "Notch", before: (AsyncPlayerPreLoginEvent) -> Unit = {}) =
        AsyncPlayerPreLoginEvent(name, InetAddress.getLoopbackAddress(), uuid).also {
            before(it)
            plugin.onPreLogin(it)
            plugin.onPreLoginResult(it)
        }

    private fun player(uuid: UUID = NOTCH, name: String = "Notch", op: Boolean = false, bypass: Boolean = false): Player =
        mock(Player::class.java).also {
            `when`(it.uniqueId).thenReturn(uuid)
            `when`(it.name).thenReturn(name)
            `when`(it.isOp).thenReturn(op)
            `when`(it.hasPermission(MinecraftAuthService.BYPASS_PERMISSION)).thenReturn(bypass)
        }

    private fun login(player: Player) = PlayerLoginEvent(player, "localhost", InetAddress.getLoopbackAddress()).also(plugin::onLogin)

    private fun operator(uuid: UUID): OfflinePlayer = mock(OfflinePlayer::class.java).also { `when`(it.uniqueId).thenReturn(uuid) }

    @Test
    fun `a player who meets a condition gets in`() {
        fake.following = setOf(Platform.TWITCH)
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, preLogin().loginResult)
        assertEquals(PlayerLoginEvent.Result.ALLOWED, login(player()).result)
        assertEquals(listOf("following:twitch"), fake.calls)
    }

    @Test
    fun `an operator gets bypass without any check`() {
        operators += operator(UUID.randomUUID())
        operators += operator(NOTCH)

        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, preLogin().loginResult)
        assertEquals(PlayerLoginEvent.Result.ALLOWED, login(player(op = true)).result)
        assertTrue(fake.calls.isEmpty(), fake.calls.toString())
    }

    @Test
    fun `operators are checked like anyone else without bypass-admins`() {
        configure(testConfig("TwitchFollower()", bypassAdmins = false))
        operators += operator(NOTCH)

        val event = preLogin()
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, event.loginResult)
        assertEquals("§cnope", event.kickMessage)
    }

    @Test
    fun `a denied player gets the kick message once they log in`() {
        // with bypass-admins on, the denial waits for PlayerLoginEvent, where permissions can be checked
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, preLogin().loginResult)

        val event = login(player())
        assertEquals(PlayerLoginEvent.Result.KICK_WHITELIST, event.result)
        assertEquals("§cnope", event.kickMessage)
    }

    @Test
    fun `a denied player is kicked at pre-login without bypass-admins`() {
        configure(testConfig("TwitchFollower()", bypassAdmins = false))

        val event = preLogin()
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, event.loginResult)
        assertEquals("§cnope", event.kickMessage)
        // nothing is left pending for the login event
        assertEquals(PlayerLoginEvent.Result.ALLOWED, login(player()).result)
    }

    @Test
    fun `a player with the bypass permission gets in`() {
        preLogin()
        assertEquals(PlayerLoginEvent.Result.ALLOWED, login(player(bypass = true)).result)
    }

    @Test
    fun `a player who is an operator by login time gets in`() {
        preLogin()
        assertEquals(PlayerLoginEvent.Result.ALLOWED, login(player(op = true)).result)
    }

    @Test
    fun `an already-disallowed login is left alone`() {
        val event = preLogin { it.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, "banned") }

        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, event.loginResult)
        assertEquals("banned", event.kickMessage)
        assertTrue(fake.calls.isEmpty(), "no API call for a login that's already denied: ${fake.calls}")
        assertEquals(PlayerLoginEvent.Result.ALLOWED, login(player()).result)
    }

    @Test
    fun `a login another plugin denies after us leaves nothing pending`() {
        val event = AsyncPlayerPreLoginEvent("Notch", InetAddress.getLoopbackAddress(), NOTCH)
        plugin.onPreLogin(event)
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "other plugin")
        plugin.onPreLoginResult(event)

        // the next attempt passes (say they followed in between): no stale denial may kick them
        fake.following = setOf(Platform.TWITCH)
        configure(testConfig("TwitchFollower()"))
        preLogin()
        assertEquals(PlayerLoginEvent.Result.ALLOWED, login(player()).result)
    }

    @Test
    fun `a login already kicked by the server keeps its own reason`() {
        preLogin()
        val event = PlayerLoginEvent(player(), "localhost", InetAddress.getLoopbackAddress())
        event.disallow(PlayerLoginEvent.Result.KICK_FULL, "full")
        plugin.onLogin(event)

        assertEquals(PlayerLoginEvent.Result.KICK_FULL, event.result)
        assertEquals("full", event.kickMessage)
        // and the denial was used up
        assertEquals(PlayerLoginEvent.Result.ALLOWED, login(player()).result)
    }

    @Test
    fun `denials are kept per player`() {
        val other = UUID.randomUUID()
        preLogin(other, "Other")

        assertEquals(PlayerLoginEvent.Result.ALLOWED, login(player()).result) // Notch never tried
        assertEquals(PlayerLoginEvent.Result.KICK_WHITELIST, login(player(other, "Other")).result)
    }

    @Test
    fun `api errors kick with the error message`() {
        fake.failure = me.minecraftauth.lib.UnavailableException("down", 503)
        preLogin()
        assertEquals("§cbroken", login(player()).kickMessage)
    }

    @Test
    fun `nothing happens while the plugin is disabled`() {
        plugin.service = null
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, preLogin().loginResult)
        assertEquals(PlayerLoginEvent.Result.ALLOWED, login(player()).result)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `the command answers line by line`() {
        val sender = mock(CommandSender::class.java)
        assertTrue(plugin.onCommand(sender, mock(Command::class.java), "mcauth", arrayOf()))

        val sent = mockingDetails(sender).invocations.filter { it.method.name == "sendMessage" }.map { it.arguments[0] as String }
        assertEquals(2, sent.size, sent.toString())
        assertTrue(sent[0].startsWith("§eMinecraftAuth §7v"), sent.toString())
        assertEquals("§7Gatekeeper: §f1§7 condition(s)", sent[1])
    }

    @Test
    fun `reload is suggested only to those who may reload`() {
        val admin = mock(CommandSender::class.java)
        `when`(admin.hasPermission(MinecraftAuthService.RELOAD_PERMISSION)).thenReturn(true)
        val user = mock(CommandSender::class.java)
        val command = mock(Command::class.java)

        assertEquals(listOf("reload"), plugin.onTabComplete(admin, command, "mcauth", arrayOf("re")))
        assertEquals(emptyList(), plugin.onTabComplete(admin, command, "mcauth", arrayOf("x")))
        assertEquals(emptyList(), plugin.onTabComplete(user, command, "mcauth", arrayOf("")))
    }
}
