package me.minecraftauth.minecraft.sponge

import me.minecraftauth.lib.Platform
import me.minecraftauth.minecraft.FakeMembership
import me.minecraftauth.minecraft.NOTCH
import me.minecraftauth.minecraft.Settings
import me.minecraftauth.minecraft.testConfig
import me.minecraftauth.minecraft.testService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.apache.logging.log4j.Logger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import org.spongepowered.api.event.lifecycle.ConstructPluginEvent
import org.spongepowered.api.event.network.ServerSideConnectionEvent
import org.spongepowered.api.profile.GameProfile
import org.spongepowered.plugin.PluginContainer
import java.io.File
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drives the plugin's auth listener with mocked Sponge events. */
class MinecraftAuthSpongeTest {

    @TempDir lateinit var dir: File

    private val fake = FakeMembership()
    private val logger = mock(Logger::class.java)
    private lateinit var plugin: MinecraftAuthSponge

    @BeforeEach
    fun setUp() {
        plugin = MinecraftAuthSponge(mock(PluginContainer::class.java), logger, dir.toPath())
        plugin.onConstruct(mock(ConstructPluginEvent::class.java))
        plugin.service = testService(dir, testConfig("TwitchFollower()"), fake)
    }

    private fun auth(uuid: UUID = NOTCH, name: String? = "Notch", cancelled: Boolean = false): ServerSideConnectionEvent.Auth {
        val profile = mock(GameProfile::class.java)
        `when`(profile.uniqueId()).thenReturn(uuid)
        `when`(profile.name()).thenReturn(Optional.ofNullable(name))
        val event = mock(ServerSideConnectionEvent.Auth::class.java)
        `when`(event.profile()).thenReturn(profile)
        `when`(event.isCancelled).thenReturn(cancelled)
        plugin.onAuth(event)
        return event
    }

    private fun Any.calls(method: String) = mockingDetails(this).invocations.filter { it.method.name == method }

    private fun Any.sentMessage(): String? =
        calls("setMessage").singleOrNull()?.let { LegacyComponentSerializer.legacySection().serialize(it.arguments[0] as Component) }

    @Test
    fun `constructing the plugin writes the default config`() {
        assertTrue(File(dir, Settings.FILE_NAME).readText().contains("server-token"))
    }

    @Test
    fun `a player who meets a condition gets in`() {
        fake.following = setOf(Platform.TWITCH)
        val event = auth()
        assertTrue(event.calls("setCancelled").isEmpty())
        assertEquals(listOf("following:twitch"), fake.calls)
    }

    @Test
    fun `a denied player is disconnected with the message`() {
        val event = auth()
        assertEquals<Any?>(true, event.calls("setCancelled").single().arguments[0])
        assertEquals("§cnope", event.sentMessage())
    }

    @Test
    fun `api errors disconnect with the error message`() {
        fake.failure = me.minecraftauth.lib.UnavailableException("down", 503)
        assertEquals("§cbroken", auth().sentMessage())
    }

    @Test
    fun `a cancelled auth is left alone`() {
        val event = auth(cancelled = true)
        assertTrue(event.calls("setCancelled").isEmpty())
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `admins come in through the bypass list`() {
        plugin.service = testService(dir, testConfig("TwitchFollower()", bypass = listOf("Notch")), fake)
        assertTrue(auth().calls("setCancelled").isEmpty())
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `a profile without a name is checked by uuid`() {
        plugin.service = testService(dir, testConfig("TwitchFollower()", bypass = listOf(NOTCH.toString())), fake)
        assertTrue(auth(name = null).calls("setCancelled").isEmpty())

        val stranger = auth(UUID.randomUUID(), name = null)
        assertEquals("§cnope", stranger.sentMessage())
    }
}
