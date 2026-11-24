package me.minecraftauth.minecraft

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MinecraftAuthCommandTest {

    private class Source(private vararg val permissions: String) : CommandSource {
        val messages = mutableListOf<String>()
        override fun hasPermission(permission: String) = permission in permissions
        override fun sendMessage(message: String) { messages += message }
    }

    private val version = MinecraftAuthService.VERSION
    private val header = "§eMinecraftAuth §7v$version (Test) §8- §fhttps://minecraftauth.me"

    @Test
    fun `reload without permission is refused`(@TempDir dir: File) {
        val logger = RecordingLogger()
        val service = testService(dir, testConfig("TwitchFollower()"), logger = logger)
        File(dir, Settings.FILE_NAME).writeText(testConfig("YouTubeMember()"))

        val source = Source()
        MinecraftAuthCommand.execute(service, source, listOf("reload"))

        assertEquals(listOf("§cYou don't have permission to do that."), source.messages)
        assertEquals(listOf("TwitchFollower()"), service.settings.global.conditions)
        assertTrue(logger.lines.none { it.contains("Reloaded") })
    }

    @Test
    fun `reload with permission reloads`(@TempDir dir: File) {
        val logger = RecordingLogger()
        val service = testService(dir, testConfig("TwitchFollower()"), logger = logger)
        File(dir, Settings.FILE_NAME).writeText(testConfig("YouTubeMember()"))

        val source = Source(MinecraftAuthService.RELOAD_PERMISSION)
        MinecraftAuthCommand.execute(service, source, listOf("RELOAD"))

        assertEquals(listOf("§aMinecraftAuth configuration reloaded."), source.messages)
        assertEquals(listOf("YouTubeMember()"), service.settings.global.conditions)
        assertTrue("Reloaded MinecraftAuth.yml" in logger.infos())
    }

    @Test
    fun `a failed reload reports the error and keeps the old configuration`(@TempDir dir: File) {
        val fake = FakeMembership()
        val logger = RecordingLogger()
        val service = testService(dir, testConfig("TwitchFollower()"), fake, logger)
        File(dir, Settings.FILE_NAME).writeText("gatekeeper:\n  conditions: [\"unterminated\n  : : :\n")

        val source = Source(MinecraftAuthService.RELOAD_PERMISSION)
        MinecraftAuthCommand.execute(service, source, listOf("reload"))

        assertTrue(source.messages.single().startsWith("§cCouldn't reload MinecraftAuth.yml: "), source.messages.toString())
        assertEquals(listOf("Failed to reload MinecraftAuth.yml"), logger.errors())
        assertTrue(logger.errors.single() != null)
        // still gatekeeping with what was loaded before
        assertEquals(listOf("TwitchFollower()"), service.settings.global.conditions)
        assertTrue(service.check(NOTCH, "Notch", false).denied)
        assertEquals(listOf("following:twitch"), fake.calls)
    }

    @Test
    fun `status without a token`(@TempDir dir: File) {
        val service = testService(dir, testConfig("TwitchFollower()", token = ""))
        val source = Source()
        MinecraftAuthCommand.execute(service, source, emptyList())

        assertEquals(listOf(header, "§cNo server token configured; the gatekeeper is off."), source.messages)
    }

    @Test
    fun `status with a token`(@TempDir dir: File) {
        val service = testService(dir, testConfig("TwitchFollower()", "YouTubeMember()"))
        val source = Source()
        MinecraftAuthCommand.execute(service, source, emptyList())

        assertEquals(listOf(header, "§7Gatekeeper: §f2§7 condition(s)"), source.messages)
    }

    @Test
    fun `status on a proxy counts the backend server rule sets`(@TempDir dir: File) {
        val extra = "  servers:\n    event:\n      conditions: [\"TwitchSubscriber(3)\"]\n    lobby: {}"
        val service = testService(dir, testConfig("TwitchFollower()", extra = extra), proxy = true)
        val source = Source()
        MinecraftAuthCommand.execute(service, source, listOf("status"))

        assertEquals("§7Gatekeeper: §f1§7 condition(s), §f2§7 backend server rule set(s)", source.messages[1])
    }

    @Test
    fun `status shows the reload hint only to those who may reload`(@TempDir dir: File) {
        val service = testService(dir, testConfig("TwitchFollower()"))
        val admin = Source(MinecraftAuthService.RELOAD_PERMISSION)
        MinecraftAuthCommand.execute(service, admin, emptyList())

        assertEquals(3, admin.messages.size)
        assertEquals("§7/minecraftauth reload §8- §freload the configuration", admin.messages.last())
    }
}
