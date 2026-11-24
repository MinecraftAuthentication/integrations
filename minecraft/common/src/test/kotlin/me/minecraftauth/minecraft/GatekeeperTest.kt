package me.minecraftauth.minecraft

import me.minecraftauth.lib.ConfigurationException
import me.minecraftauth.lib.LinkedAccount
import me.minecraftauth.lib.LookupResult
import me.minecraftauth.lib.Platform
import me.minecraftauth.lib.UnavailableException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GatekeeperTest {

    private val notch = NOTCH

    private fun service(dir: File, yaml: String, membership: FakeMembership, proxy: Boolean = false, logger: PlatformLogger = RecordingLogger()) =
        testService(dir, yaml, membership, logger, proxy)

    private fun config(vararg conditions: String, extra: String = "") = testConfig(*conditions, extra = extra)

    @Test
    fun `any passing condition allows`(@TempDir dir: File) {
        val fake = FakeMembership().apply { subscribed = mapOf(Platform.TWITCH to setOf("2")) }
        val service = service(dir, config("TwitchFollower()", "TwitchSubscriber(2000)"), fake)

        assertEquals(GatekeeperResult.Type.ALLOWED, service.check(notch, "Notch", false).type)
        assertEquals(listOf("following:twitch", "subscribed:twitch:2"), fake.calls)
    }

    @Test
    fun `denied with colorized message`(@TempDir dir: File) {
        val service = service(dir, config("YouTubeMember()"), FakeMembership())
        val result = service.check(notch, "Notch", false)
        assertTrue(result.denied)
        assertEquals("§cnope", result.message)
    }

    @Test
    fun `denials carry a reason and can be logged by the platform`(@TempDir dir: File) {
        val logger = RecordingLogger()
        val fake = FakeMembership()
        val service = service(dir, config("YouTubeMember()"), fake, logger = logger)

        val quiet = service.check(notch, "Notch", false, logDenial = false)
        assertTrue(quiet.denied)
        assertEquals("none of the conditions are met", quiet.reason)
        assertTrue(logger.lines.none { it.contains("Denying") }, logger.lines.toString())

        service.check(notch, "Notch", false)
        assertTrue("Denying Notch: none of the conditions are met" in logger.infos(), logger.lines.toString())

        fake.failure = UnavailableException("down", 502)
        service.reload()
        assertEquals("their accounts couldn't be checked", service.check(notch, "Notch", false, logDenial = false).reason)
    }

    @Test
    fun `boolean operators short-circuit`(@TempDir dir: File) {
        val fake = FakeMembership().apply {
            following = setOf(Platform.TWITCH)
            roles = setOf("1:Banned")
        }
        val service = service(dir, config("TwitchFollower() or DiscordServer(\"1\")", "YouTubeSubscriber() and DiscordServer(\"2\")", "TwitchFollower() and not(DiscordRole(\"1\", \"Banned\"))"), fake)

        assertEquals(GatekeeperResult.Type.ALLOWED, service.check(notch, "Notch", false).type)
        // first condition passes on TwitchFollower alone; DiscordServer("1") is never asked
        assertEquals(listOf("following:twitch"), fake.calls)
    }

    @Test
    fun `not() is honoured`(@TempDir dir: File) {
        val fake = FakeMembership().apply {
            following = setOf(Platform.TWITCH)
            roles = setOf("1:Banned")
        }
        val service = service(dir, config("TwitchFollower() and not(DiscordRole(\"1\", \"Banned\"))"), fake)
        assertTrue(service.check(notch, "Notch", false).denied)
    }

    @Test
    fun `discord roles are checked in the given server`(@TempDir dir: File) {
        val fake = FakeMembership().apply { roles = setOf("555:Subscriber") }
        val service = service(dir, config("DiscordRole(\"123\")", "DiscordRole(\"555\", \"Subscriber\")"), fake)

        assertEquals(GatekeeperResult.Type.ALLOWED, service.check(notch, "Notch", false).type)
        assertEquals(listOf("role:null:123", "role:555:Subscriber"), fake.calls)
    }

    @Test
    fun `a discord role name without its server is rejected`(@TempDir dir: File) {
        // anyone can add the bot to their own server and create a role with this name
        val fake = FakeMembership().apply { roles = setOf("null:Subscriber") }
        val service = service(dir, config("DiscordRole(\"Subscriber\")", "TwitchFollower() or DiscordRole(\"VIP\")",
            "DiscordRole(\"not-a-server\", \"VIP\")", "DiscordRole(\"1\", \"2\", \"3\")", "YouTubeMember()"), fake)

        assertTrue(service.check(notch, "Notch", false).denied)
        // only the valid condition was kept
        assertEquals(listOf("subscribed:google:null"), fake.calls)
    }

    @Test
    fun `answers are cached`(@TempDir dir: File) {
        val fake = FakeMembership()
        val service = service(dir, config("PatreonMember(\"Gold\")"), fake)
        service.check(notch, "Notch", false)
        service.check(notch, "Notch", false)
        assertEquals(listOf("subscribed:patreon:Gold"), fake.calls)
    }

    @Test
    fun `linked condition`(@TempDir dir: File) {
        val fake = FakeMembership().apply {
            linked = LookupResult(mapOf(Platform.MINECRAFT to LinkedAccount(notch.toString(), false), Platform.DISCORD to LinkedAccount(null, true)))
        }
        val service = service(dir, config("Linked(\"twitch\")", "Linked(\"discord\")"), fake)
        assertEquals(GatekeeperResult.Type.ALLOWED, service.check(notch, "Notch", false).type)
    }

    @Test
    fun `bypass by name, uuid and admin`(@TempDir dir: File) {
        val fake = FakeMembership()
        val yaml = testConfig("TwitchFollower()", bypass = listOf("notch", "853c80ef3c3749fdaa49938b674adae6"))
        val service = service(dir, yaml, fake)
        assertEquals(GatekeeperResult.Type.BYPASSED, service.check(notch, "Notch", false).type)
        assertEquals(GatekeeperResult.Type.BYPASSED, service.check(UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6"), "jeb_", false).type)
        assertEquals(GatekeeperResult.Type.BYPASSED, service.check(UUID.randomUUID(), "Admin", true).type)
        assertTrue(service.check(UUID.randomUUID(), "Someone", false).denied)
        assertTrue(fake.calls.size == 1, "only Someone was checked: ${fake.calls}")
    }

    @Test
    fun `admins are checked like anyone else without bypass-admins`(@TempDir dir: File) {
        val service = service(dir, testConfig("TwitchFollower()", bypassAdmins = false), FakeMembership())
        assertTrue(service.check(notch, "Notch", true).denied)
    }

    @Test
    fun `api errors follow policy`(@TempDir dir: File) {
        val fake = FakeMembership().apply { failure = UnavailableException("down", 502) }
        val deny = service(dir, config("TwitchFollower()"), fake)
        assertEquals("§cbroken", deny.check(notch, "Notch", false).message)

        val allow = service(dir, testConfig("TwitchFollower()", onApiError = "allow"), fake)
        assertEquals(GatekeeperResult.Type.ALLOWED, allow.check(notch, "Notch", false).type)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403])
    fun `configuration errors deny even when on-api-error is allow`(status: Int, @TempDir dir: File) {
        val fake = FakeMembership().apply { failure = ConfigurationException("bad token", status) }
        val logger = RecordingLogger()
        val service = service(dir, testConfig("TwitchFollower()", onApiError = "allow"), fake, logger = logger)

        val result = service.check(notch, "Notch", false)
        assertTrue(result.denied)
        assertEquals("§cbroken", result.message)
        assertTrue(logger.warnings().single().contains("configuration problem"), logger.lines.toString())
    }

    @Test
    fun `a failure followed by a passing condition lets the player in`(@TempDir dir: File) {
        val fake = FakeMembership().apply {
            failures["following:twitch"] = UnavailableException("down", 502)
            subscribed = mapOf(Platform.PATREON to setOf(null))
        }
        val logger = RecordingLogger()
        val service = service(dir, config("TwitchFollower()", "PatreonMember()"), fake, logger = logger)

        assertEquals(GatekeeperResult.Type.ALLOWED, service.check(notch, "Notch", false).type)
        assertEquals(listOf("following:twitch", "subscribed:patreon:null"), fake.calls)
        // the outage is still reported, but the player isn't denied
        assertTrue(logger.warnings().single().startsWith("Couldn't check 'TwitchFollower()' for Notch"), logger.lines.toString())
        assertTrue(logger.lines.none { it.contains("Denying") })
    }

    @Test
    fun `a failure followed by a passing condition lets the player in after a configuration error too`(@TempDir dir: File) {
        val fake = FakeMembership().apply {
            failures["following:twitch"] = ConfigurationException("bad token", 403)
            following = setOf(Platform.GOOGLE)
        }
        val service = service(dir, config("TwitchFollower()", "YouTubeSubscriber()"), fake)
        assertEquals(GatekeeperResult.Type.ALLOWED, service.check(notch, "Notch", false).type)
    }

    @Test
    fun `unexpected exceptions count as api errors`(@TempDir dir: File) {
        val fake = FakeMembership().apply { failure = IllegalStateException("boom") }
        val logger = RecordingLogger()
        val deny = service(dir, config("TwitchFollower()"), fake, logger = logger)
        assertEquals("§cbroken", deny.check(notch, "Notch", false).message)
        assertEquals(listOf("Error evaluating 'TwitchFollower()' for Notch"), logger.errors())
        assertTrue(logger.errors.single() is IllegalStateException)

        val allow = service(dir, testConfig("TwitchFollower()", onApiError = "allow"), fake)
        assertEquals(GatekeeperResult.Type.ALLOWED, allow.check(notch, "Notch", false).type)
    }

    @Test
    fun `logging during an outage is rate-limited`(@TempDir dir: File) {
        val fake = FakeMembership().apply { failure = UnavailableException("down", 502) }
        val logger = RecordingLogger()
        val service = service(dir, config("TwitchFollower()", "YouTubeMember()"), fake, logger = logger)

        repeat(5) { service.check(UUID.randomUUID(), "Player$it", false) }

        // 5 players x 2 conditions failed, but the outage is only reported once a minute
        assertEquals(10, fake.calls.size)
        assertEquals(1, logger.warnings().size, logger.lines.toString())
        // a configuration problem is a different problem, so it's reported even during the outage
        fake.failure = ConfigurationException("bad token", 401)
        repeat(3) { service.check(UUID.randomUUID(), "Other$it", false) }
        assertEquals(2, logger.warnings().size, logger.lines.toString())
        assertTrue(logger.warnings().last().contains("configuration problem"))
        // denials are still logged one by one
        assertEquals(8, logger.infos().count { it.startsWith("Denying") })
    }

    @Test
    fun `rate-limited log speaks again after the interval`() {
        val logger = RecordingLogger()
        val log = RateLimitedLog(logger, intervalMillis = 0)
        log.warn("k", "one")
        log.warn("k", "two")
        assertEquals(listOf("one", "two"), logger.warnings())

        val slow = RateLimitedLog(logger, intervalMillis = 60_000)
        slow.warn("a", "three")
        slow.warn("a", "four")
        slow.warn("b", "five")
        assertEquals(listOf("one", "two", "three", "five"), logger.warnings())
    }

    @Test
    fun `invalid conditions are skipped`(@TempDir dir: File) {
        val fake = FakeMembership().apply { following = setOf(Platform.GOOGLE) }
        val logger = RecordingLogger()
        val service = service(dir, config("NotAFunction()", "YouTubeSubscriber("), fake, logger = logger)
        assertEquals(GatekeeperResult.Type.NOT_ENABLED, service.check(notch, "Notch", false).type)
        assertEquals(2, logger.errors().count { it.startsWith("Ignoring invalid condition") }, logger.lines.toString())
    }

    @Test
    fun `no token or no conditions means not enabled`(@TempDir dir: File) {
        val logger = RecordingLogger()
        val noToken = service(dir, testConfig("TwitchFollower()", token = ""), FakeMembership(), logger = logger)
        assertEquals(GatekeeperResult.Type.NOT_ENABLED, noToken.check(notch, "Notch", false).type)
        assertTrue(logger.warnings().single().startsWith("No server token is set"))

        val noConditions = service(dir, testConfig(), FakeMembership())
        assertEquals(GatekeeperResult.Type.NOT_ENABLED, noConditions.check(notch, "Notch", false).type)
    }

    @Test
    fun `per-server realms inherit messages`(@TempDir dir: File) {
        val fake = FakeMembership().apply { following = setOf(Platform.TWITCH) }
        val extra = "  servers:\n    event:\n      conditions:\n        - TwitchSubscriber(3)\n    lobby: {}"
        val service = service(dir, config("TwitchFollower()", extra = extra), fake, proxy = true)

        assertEquals(GatekeeperResult.Type.ALLOWED, service.check(notch, "Notch", false).type)
        assertEquals("§cnope", service.check(notch, "Notch", false, "event").message)
        assertEquals(GatekeeperResult.Type.NOT_ENABLED, service.check(notch, "Notch", false, "lobby").type)
        assertEquals(GatekeeperResult.Type.NOT_ENABLED, service.check(notch, "Notch", false, "unknown").type)
    }

    @Test
    fun `per-server error message is used when that server's check fails`(@TempDir dir: File) {
        val fake = FakeMembership().apply { failure = UnavailableException("down", 503) }
        val extra = "  servers:\n    event:\n      conditions:\n        - TwitchSubscriber(3)\n      messages:\n        error: \"&eevent down\""
        val service = service(dir, config("TwitchFollower()", extra = extra), fake, proxy = true)

        assertEquals("§cbroken", service.check(notch, "Notch", false).message)
        assertEquals("§eevent down", service.check(notch, "Notch", false, "event").message)
    }
}
