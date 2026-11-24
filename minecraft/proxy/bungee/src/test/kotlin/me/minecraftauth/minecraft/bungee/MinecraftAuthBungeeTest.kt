package me.minecraftauth.minecraft.bungee

import me.minecraftauth.lib.Platform
import me.minecraftauth.minecraft.FakeMembership
import me.minecraftauth.minecraft.MinecraftAuthService
import me.minecraftauth.minecraft.NOTCH
import me.minecraftauth.minecraft.testConfig
import me.minecraftauth.minecraft.testService
import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.config.ConfigurationAdapter
import net.md_5.bungee.api.config.ServerInfo
import net.md_5.bungee.api.connection.PendingConnection
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.connection.Server
import net.md_5.bungee.api.event.LoginEvent
import net.md_5.bungee.api.event.ServerConnectEvent
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.plugin.PluginDescription
import net.md_5.bungee.api.scheduler.TaskScheduler
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import java.io.File
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Drives the plugin's listeners with real BungeeCord events; the proxy and players are mocks. */
class MinecraftAuthBungeeTest {

    @TempDir lateinit var dir: File

    private val fake = FakeMembership()
    private val groups = mutableMapOf<String, Collection<String>>()
    private val permissions = mutableMapOf<String, Collection<String>>()
    private var asyncTasks = 0
    private lateinit var plugin: MinecraftAuthBungee

    private val servers = """
          servers:
            event:
              conditions: ["TwitchSubscriber(3)"]
              messages:
                denied: "&eevent only"
    """.trimIndent().prependIndent("  ")

    @BeforeEach
    fun setUp() {
        val proxy = mock(ProxyServer::class.java)
        `when`(proxy.logger).thenReturn(Logger.getLogger("MinecraftAuthBungeeTest"))
        `when`(proxy.pluginsFolder).thenReturn(dir)
        val scheduler = mock(TaskScheduler::class.java)
        `when`(scheduler.runAsync(any(), any())).thenAnswer {
            asyncTasks++
            (it.arguments[1] as Runnable).run()
            null
        }
        `when`(proxy.scheduler).thenReturn(scheduler)
        val config = mock(ConfigurationAdapter::class.java)
        `when`(config.getGroups(anyString())).thenAnswer { groups[it.arguments[0]] ?: emptyList<String>() }
        `when`(config.getPermissions(anyString())).thenAnswer { permissions[it.arguments[0]] ?: emptyList<String>() }
        `when`(proxy.configurationAdapter).thenReturn(config)

        val description = PluginDescription().apply { name = "MinecraftAuth"; main = MinecraftAuthBungee::class.java.name }
        plugin = MinecraftAuthBungee(proxy, description)
        // BungeeCord's test constructor only skips the class loader check; its loader calls the package-private init
        Plugin::class.java.getDeclaredMethod("init", ProxyServer::class.java, PluginDescription::class.java)
            .apply { isAccessible = true }
            .invoke(plugin, proxy, description)
        plugin.service = testService(dir, testConfig("TwitchFollower()", extra = servers), fake, proxy = true)
    }

    private fun connection(uuid: UUID? = NOTCH, name: String = "Notch"): PendingConnection = mock(PendingConnection::class.java).also {
        `when`(it.uniqueId).thenReturn(uuid)
        `when`(it.name).thenReturn(name)
    }

    /** Fires a login through the plugin; [done] tells whether BungeeCord could carry on with it (every intent completed). */
    private fun login(connection: PendingConnection, before: (LoginEvent) -> Unit = {}): Pair<LoginEvent, Boolean> {
        var done = false
        val event = LoginEvent(connection) { _, _ -> done = true }
        before(event)
        plugin.onLogin(event)
        event.postCall()
        return event to done
    }

    private fun player(admin: Boolean = false, connected: Boolean = false): ProxiedPlayer = mock(ProxiedPlayer::class.java).also {
        `when`(it.uniqueId).thenReturn(NOTCH)
        `when`(it.name).thenReturn("Notch")
        `when`(it.hasPermission(MinecraftAuthService.BYPASS_PERMISSION)).thenReturn(admin)
        `when`(it.server).thenReturn(if (connected) mock(Server::class.java) else null)
    }

    private fun target(name: String): ServerInfo = mock(ServerInfo::class.java).also { `when`(it.name).thenReturn(name) }

    private fun legacy(components: Array<*>?) = TextComponent.toLegacyText(*(components ?: emptyArray<BaseComponent>()).map { it as BaseComponent }.toTypedArray())

    /** What [method] (varargs of components) was called with, one entry per call. */
    private fun Any.sent(method: String) =
        mockingDetails(this).invocations.filter { it.method.name == method }.map { legacy(it.rawArguments[0] as Array<*>) }

    @Test
    fun `a player who meets the proxy's conditions logs in`() {
        fake.following = setOf(Platform.TWITCH)
        val (event, done) = login(connection())
        assertFalse(event.isCancelled)
        assertTrue(done, "the login intent must be completed")
        assertEquals(1, asyncTasks)
    }

    @Test
    fun `a denied player is refused with the message`() {
        val (event, done) = login(connection())
        assertTrue(event.isCancelled)
        assertEquals("§cnope", event.reason.toLegacyText())
        assertTrue(done)
    }

    @Test
    fun `api errors refuse with the error message`() {
        fake.failure = me.minecraftauth.lib.UnavailableException("down", 503)
        val (event, _) = login(connection())
        assertEquals("§cbroken", event.reason.toLegacyText())
    }

    @Test
    fun `admins come in through BungeeCord's permission groups`() {
        groups["Notch"] = listOf("admin")
        permissions["admin"] = listOf(MinecraftAuthService.BYPASS_PERMISSION)
        assertFalse(login(connection()).first.isCancelled)

        groups["Notch"] = listOf("owner")
        permissions["owner"] = listOf("*")
        assertFalse(login(connection()).first.isCancelled)
        assertTrue(fake.calls.isEmpty())

        permissions["owner"] = listOf("bungeecord.command.server")
        assertTrue(login(connection()).first.isCancelled)
    }

    @Test
    fun `a cancelled login is left alone`() {
        val (event, done) = login(connection()) { it.isCancelled = true }
        assertTrue(event.isCancelled)
        assertTrue(done)
        assertEquals(0, asyncTasks)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `offline-mode logins without a uuid are not checked`() {
        val (event, done) = login(connection(uuid = null))
        assertFalse(event.isCancelled)
        assertTrue(done)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `joining a backend server with its own conditions`() {
        fake.subscribed = mapOf(Platform.TWITCH to setOf("3"))
        val event = ServerConnectEvent(player(), target("event"), ServerConnectEvent.Reason.COMMAND, null)
        plugin.onServerConnect(event)
        assertFalse(event.isCancelled)
        assertEquals(listOf("subscribed:twitch:3"), fake.calls)
    }

    @Test
    fun `a player denied on their first server is disconnected`() {
        val player = player()
        val event = ServerConnectEvent(player, target("event"), ServerConnectEvent.Reason.JOIN_PROXY, null)
        plugin.onServerConnect(event)

        assertTrue(event.isCancelled)
        assertEquals(listOf("§eevent only"), player.sent("disconnect"))
        assertTrue(player.sent("sendMessage").isEmpty())
    }

    @Test
    fun `a player denied while switching servers stays where they are and is told why`() {
        val player = player(connected = true)
        val event = ServerConnectEvent(player, target("event"), ServerConnectEvent.Reason.COMMAND, null)
        plugin.onServerConnect(event)

        assertTrue(event.isCancelled)
        assertEquals(listOf("§eevent only"), player.sent("sendMessage"))
        assertTrue(player.sent("disconnect").isEmpty())
    }

    @Test
    fun `players with the bypass permission switch freely`() {
        val event = ServerConnectEvent(player(admin = true), target("event"), ServerConnectEvent.Reason.COMMAND, null)
        plugin.onServerConnect(event)
        assertFalse(event.isCancelled)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `servers without their own conditions are open`() {
        val event = ServerConnectEvent(player(), target("lobby"), ServerConnectEvent.Reason.COMMAND, null)
        plugin.onServerConnect(event)
        assertFalse(event.isCancelled)
        assertTrue(fake.calls.isEmpty())
    }
}
