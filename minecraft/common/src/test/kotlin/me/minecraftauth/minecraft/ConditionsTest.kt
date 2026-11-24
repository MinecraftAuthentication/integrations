package me.minecraftauth.minecraft

import com.udojava.evalex.Expression
import me.minecraftauth.lib.LinkedAccount
import me.minecraftauth.lib.LookupResult
import me.minecraftauth.lib.Platform
import me.minecraftauth.lib.UnavailableException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConditionsTest {

    private val fake = FakeMembership()
    private val conditions = Conditions(fake, cacheSeconds = 60)

    private fun rejected(condition: String): String =
        assertFailsWith<Expression.ExpressionException>("'$condition' should be rejected") { conditions.validate(condition) }.message ?: ""

    @ParameterizedTest
    @CsvSource("1,1", "1000,1", "2,2", "2000,2", "3,3", "3000,3", "'\"1000\"',1", "' 2 ',2", "2.0,2")
    fun `twitch tiers are normalised`(argument: String, tier: String) {
        conditions.validate("TwitchSubscriber($argument)")
        conditions.evaluate("TwitchSubscriber($argument)", NOTCH)
        assertEquals(listOf("subscribed:twitch:$tier"), fake.calls)
    }

    @Test
    fun `twitch subscriber without a tier means any tier`() {
        conditions.evaluate("TwitchSubscriber()", NOTCH)
        assertEquals(listOf("subscribed:twitch:null"), fake.calls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["4", "0", "-1", "999", "4000", "\"abc\"", "\"\""])
    fun `bad twitch tiers are rejected`(argument: String) {
        assertTrue(rejected("TwitchSubscriber($argument)").contains("tier must be 1, 2 or 3"))
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `validation never asks the api`() {
        conditions.validate("TwitchFollower() and TwitchSubscriber(2) or YouTubeSubscriber() || YouTubeMember() && " +
            "PatreonMember(\"Gold\") or DiscordServer(\"1\") or DiscordRole(\"2\") or DiscordRole(\"1\", \"VIP\") or Linked() or Linked(\"discord\")")
        assertTrue(fake.calls.isEmpty(), fake.calls.toString())
    }

    @Test
    fun `validation reaches functions behind short-circuits`() {
        // eager validation evaluates both sides, so a bad argument on the right is still found
        assertTrue(rejected("TwitchFollower() or TwitchSubscriber(9)").contains("tier"))
        assertTrue(rejected("TwitchFollower() and Linked(\"bogus\")").contains("bogus"))
    }

    @Test
    fun `unknown platforms in Linked are rejected`() {
        assertTrue(rejected("Linked(\"bogus\")").contains("Unknown platform 'bogus'"))
        conditions.validate("Linked(\"youtube\")") // an alias of google
    }

    @Test
    fun `Linked with a platform needs that platform in the result`() {
        fake.linked = LookupResult(mapOf(Platform.MINECRAFT to LinkedAccount(NOTCH.toString(), false), Platform.TWITCH to LinkedAccount(null, true)))
        assertTrue(conditions.evaluate("Linked()", NOTCH))
        assertTrue(conditions.evaluate("Linked(\"twitch\")", NOTCH))
        assertFalse(conditions.evaluate("Linked(\"patreon\")", NOTCH))
        assertEquals(listOf("lookup:", "lookup:twitch", "lookup:patreon"), fake.calls)

        fake.linked = null
        val unlinked = Conditions(fake, 0)
        assertFalse(unlinked.evaluate("Linked()", NOTCH))
    }

    @ParameterizedTest
    @ValueSource(strings = ["DiscordServer()", "DiscordServer(\"1\", \"2\")", "TwitchFollower(\"x\")", "YouTubeSubscriber(1)"])
    fun `fixed-arity functions reject the wrong number of arguments`(condition: String) {
        assertTrue(rejected(condition).contains("expected"), condition)
    }

    @Test
    fun `discord role arguments are checked`() {
        assertTrue(rejected("DiscordRole()").contains("takes a role ID"))
        assertTrue(rejected("DiscordRole(\"1\", \"2\", \"3\")").contains("takes a role ID"))
        assertTrue(rejected("DiscordRole(\" \")").contains("needs a role ID or name"))
        assertTrue(rejected("DiscordRole(\"Subscriber\")").contains("needs its server"))
        assertTrue(rejected("DiscordRole(\"guild\", \"Subscriber\")").contains("must be a Discord server ID"))
    }

    @Test
    fun `unknown functions and syntax errors are rejected`() {
        rejected("NotAFunction()")
        rejected("TwitchFollower(")
        rejected("TwitchFollower() and")
    }

    @ParameterizedTest
    @CsvSource(
        "TwitchFollower() && YouTubeSubscriber(), TwitchFollower() and YouTubeSubscriber()",
        "TwitchFollower() || YouTubeSubscriber(), TwitchFollower() or YouTubeSubscriber()",
        "YouTubeMember() || TwitchFollower() && YouTubeSubscriber(), YouTubeMember() or TwitchFollower() and YouTubeSubscriber()",
    )
    fun `symbol operators work like the word operators`(symbols: String, words: String) {
        for (following in listOf(emptySet(), setOf(Platform.TWITCH), setOf(Platform.GOOGLE), setOf(Platform.TWITCH, Platform.GOOGLE))) {
            val a = FakeMembership().apply { this.following = following }
            val b = FakeMembership().apply { this.following = following }
            assertEquals(Conditions(b, 0).evaluate(words, NOTCH), Conditions(a, 0).evaluate(symbols, NOTCH), "$following: $symbols")
            // and they short-circuit the same way
            assertEquals(b.calls, a.calls, "$following: $symbols")
        }
    }

    @Test
    fun `and binds tighter than or`() {
        fake.following = setOf(Platform.TWITCH)
        // YouTubeMember() or (TwitchFollower() and YouTubeSubscriber()) = false or (true and false)
        assertFalse(conditions.evaluate("YouTubeMember() or TwitchFollower() and YouTubeSubscriber()", NOTCH))
        assertTrue(conditions.evaluate("(YouTubeMember() or TwitchFollower()) and not(YouTubeSubscriber())", NOTCH))
    }

    @Test
    fun `symbol operators short-circuit`() {
        fake.following = setOf(Platform.TWITCH)
        assertTrue(conditions.evaluate("TwitchFollower() || YouTubeSubscriber()", NOTCH))
        assertFalse(conditions.evaluate("YouTubeMember() && PatreonMember()", NOTCH))
        assertEquals(listOf("following:twitch", "subscribed:google:null"), fake.calls)
    }

    @Test
    fun `exceptions are never cached`() {
        fake.failure = UnavailableException("down", 502)
        repeat(2) { assertFailsWith<UnavailableException> { conditions.evaluate("TwitchFollower()", NOTCH) } }
        assertEquals(2, fake.calls.size)

        fake.failure = null
        fake.following = setOf(Platform.TWITCH)
        assertTrue(conditions.evaluate("TwitchFollower()", NOTCH))
        assertTrue(conditions.evaluate("TwitchFollower()", NOTCH))
        // the success was cached; the failures before it weren't
        assertEquals(3, fake.calls.size)
    }

    @Test
    fun `answers are cached per player and argument`() {
        val other = java.util.UUID.randomUUID()
        conditions.evaluate("PatreonMember(\"Gold\")", NOTCH)
        conditions.evaluate("PatreonMember(\"gold\")", NOTCH) // tiers match ignoring case, so this is the same question
        conditions.evaluate("PatreonMember(\"Silver\")", NOTCH)
        conditions.evaluate("PatreonMember(\"Gold\")", other)
        assertEquals(listOf("subscribed:patreon:Gold", "subscribed:patreon:Silver", "subscribed:patreon:Gold"), fake.calls)
    }

    @Test
    fun `a cache of 0 seconds always asks`() {
        val uncached = Conditions(fake, 0)
        repeat(3) { uncached.evaluate("TwitchFollower()", NOTCH) }
        assertEquals(3, fake.calls.size)
    }

    @Test
    fun `invalidate clears the cache`() {
        conditions.evaluate("TwitchFollower()", NOTCH)
        fake.following = setOf(Platform.TWITCH)
        assertFalse(conditions.evaluate("TwitchFollower()", NOTCH)) // cached
        conditions.invalidate()
        assertTrue(conditions.evaluate("TwitchFollower()", NOTCH))
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun `reload clears the cache`(@TempDir dir: File) {
        val service = testService(dir, testConfig("TwitchFollower()"), fake)
        assertTrue(service.check(NOTCH, "Notch", false).denied)
        fake.following = setOf(Platform.TWITCH)
        assertTrue(service.check(NOTCH, "Notch", false).denied) // cached answer
        service.reload()
        assertEquals(GatekeeperResult.Type.ALLOWED, service.check(NOTCH, "Notch", false).type)
        assertEquals(2, fake.calls.size)
    }
}
