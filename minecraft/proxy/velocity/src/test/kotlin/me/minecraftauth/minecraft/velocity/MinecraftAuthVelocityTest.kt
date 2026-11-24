package me.minecraftauth.minecraft.velocity

import com.velocitypowered.api.event.Continuation
import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import me.minecraftauth.lib.Platform
import me.minecraftauth.minecraft.FakeMembership
import me.minecraftauth.minecraft.MinecraftAuthService
import me.minecraftauth.minecraft.NOTCH
import me.minecraftauth.minecraft.testConfig
import me.minecraftauth.minecraft.testService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import org.slf4j.helpers.NOPLogger
import java.io.File
import java.net.InetSocketAddress
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives the plugin's listeners with real Velocity events; the proxy and players are mocks. */
class MinecraftAuthVelocityTest {

    @TempDir lateinit var dir: File

    private val fake = FakeMembership()
    private lateinit var plugin: MinecraftAuthVelocity

    private val servers = """
          servers:
            event:
              conditions: ["TwitchSubscriber(3)"]
              messages:
                denied: "&eevent only"
    """.trimIndent().prependIndent("  ")

    @BeforeEach
    fun setUp() {
        plugin = MinecraftAuthVelocity(mock(ProxyServer::class.java), NOPLogger.NOP_LOGGER, dir.toPath())
        configure(testConfig("TwitchFollower()", extra = servers))
    }

    private fun configure(yaml: String) {
        plugin.service = testService(dir, yaml, fake, proxy = true)
    }

    private fun player(admin: Boolean = false, connected: Boolean = false): Player = mock(Player::class.java).also {
        `when`(it.uniqueId).thenReturn(NOTCH)
        `when`(it.username).thenReturn("Notch")
        `when`(it.hasPermission(MinecraftAuthService.BYPASS_PERMISSION)).thenReturn(admin)
        `when`(it.currentServer).thenReturn(if (connected) Optional.of(mock(ServerConnection::class.java)) else Optional.empty())
    }

    private fun server(name: String): RegisteredServer = mock(RegisteredServer::class.java).also {
        `when`(it.serverInfo).thenReturn(ServerInfo(name, InetSocketAddress.createUnresolved("localhost", 25566)))
    }

    /** Runs an async event task the way Velocity would, on this thread. */
    private fun EventTask?.run() {
        if (this == null) return
        assertTrue(requiresAsync(), "checks must not run on the network thread")
        var failure: Throwable? = null
        execute(object : Continuation {
            override fun resume() {}
            override fun resumeWithException(exception: Throwable) { failure = exception }
        })
        failure?.let { throw it }
    }

    private fun Component.legacy() = LegacyComponentSerializer.legacySection().serialize(this)

    private fun Any.sent(method: String) =
        mockingDetails(this).invocations.filter { it.method.name == method }.map { (it.arguments[0] as Component).legacy() }

    @Test
    fun `a player who meets the proxy's conditions logs in`() {
        fake.following = setOf(Platform.TWITCH)
        val event = LoginEvent(player())
        plugin.onLogin(event).run()
        assertTrue(event.result.isAllowed)
    }

    @Test
    fun `a denied player is refused with the message`() {
        val event = LoginEvent(player())
        plugin.onLogin(event).run()

        assertFalse(event.result.isAllowed)
        assertEquals("§cnope", event.result.reasonComponent.get().legacy())
    }

    @Test
    fun `players with the bypass permission skip the checks`() {
        val event = LoginEvent(player(admin = true))
        plugin.onLogin(event).run()
        assertTrue(event.result.isAllowed)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `an already-denied login is left alone`() {
        val event = LoginEvent(player())
        event.result = ResultedEvent.ComponentResult.denied(Component.text("banned"))
        assertNull(plugin.onLogin(event))
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `joining a backend server with its own conditions`() {
        fake.subscribed = mapOf(Platform.TWITCH to setOf("3"))
        val event = ServerPreConnectEvent(player(), server("event"))
        plugin.onServerPreConnect(event).run()

        assertTrue(event.result.isAllowed)
        assertEquals(listOf("subscribed:twitch:3"), fake.calls)
    }

    @Test
    fun `a player denied on their first server is disconnected`() {
        val player = player()
        val event = ServerPreConnectEvent(player, server("event"))
        plugin.onServerPreConnect(event).run()

        assertFalse(event.result.isAllowed)
        assertFalse(event.result.server.isPresent)
        assertEquals(listOf("§eevent only"), player.sent("disconnect"))
        assertTrue(player.sent("sendMessage").isEmpty())
    }

    @Test
    fun `a player denied while switching servers stays where they are and is told why`() {
        val player = player(connected = true)
        val event = ServerPreConnectEvent(player, server("event"))
        plugin.onServerPreConnect(event).run()

        assertFalse(event.result.isAllowed)
        assertEquals(listOf("§eevent only"), player.sent("sendMessage"))
        assertTrue(player.sent("disconnect").isEmpty())
    }

    @Test
    fun `servers without their own conditions are open`() {
        val event = ServerPreConnectEvent(player(), server("lobby"))
        plugin.onServerPreConnect(event).run()
        assertTrue(event.result.isAllowed)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `a connection another plugin already denied is left alone`() {
        val event = ServerPreConnectEvent(player(), server("event"))
        event.result = ServerPreConnectEvent.ServerResult.denied()
        assertNull(plugin.onServerPreConnect(event))
        assertTrue(fake.calls.isEmpty())
    }
}
