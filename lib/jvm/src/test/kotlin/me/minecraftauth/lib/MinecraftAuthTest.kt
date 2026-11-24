package me.minecraftauth.lib

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MinecraftAuthTest {

    private val api = FakeApi()
    private val uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")

    @AfterEach
    fun close() = api.close()

    @Test
    fun `lookup parses accounts and hints`() {
        api.handler = {
            FakeApi.Reply(200, """{"accounts":{"minecraft":{"identifier":"069a79f4-44e9-4726-a5be-fca90e38aaf5"},"twitch":{"hint":true},"future":{"identifier":"x"}}}""")
        }
        val result = api.client(Credentials.None).lookup(uuid, setOf(Platform.TWITCH, Platform.DISCORD))!!

        assertEquals(uuid, result.minecraft)
        assertTrue(result[Platform.TWITCH]!!.isHint)
        assertFalse(result.has(Platform.DISCORD))
        val request = api.requests.single()
        assertEquals("/api/v2/lookup/minecraft/$uuid", request.path)
        assertEquals("include=twitch%2Cdiscord", request.query)
        assertEquals("Test/1.0", request.headers["User-Agent"])
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun `lookup returns null on 404`() {
        api.handler = { error(404) }
        assertNull(api.client().lookup(Platform.DISCORD, "123"))
        assertEquals("/api/v2/lookup/discord/123", api.requests.single().path)
    }

    @Test
    fun `membership checks send the right query and credentials`() {
        api.handler = { FakeApi.Reply(200, """{"result":true}""") }
        val client = api.client(Credentials.ServerToken("secret-token"))

        assertTrue(client.following(uuid, Platform.TWITCH))
        assertTrue(client.subscribed(uuid, Platform.TWITCH, "2"))
        assertTrue(client.subscribed(uuid, Platform.GOOGLE))
        assertTrue(client.discordPresent(uuid, "1234"))

        val (following, subTwitch, subGoogle, present) = api.requests
        assertEquals("/api/v2/following", following.path)
        assertEquals("minecraft=$uuid&platform=twitch", following.query)
        assertEquals("minecraft=$uuid&platform=twitch&data=2", subTwitch.query)
        assertEquals("minecraft=$uuid&platform=google", subGoogle.query)
        assertEquals("/api/v2/discord/present", present.path)
        assertEquals("minecraft=$uuid&server=1234", present.query)
        api.requests.forEach { assertEquals("Bearer secret-token", it.headers["Authorization"]) }
    }

    @Test
    fun `discordRole sends the server with the role`() {
        api.handler = { FakeApi.Reply(200, """{"result":true}""") }
        val client = api.client()

        assertTrue(client.discordRole(uuid, "Mod Team", "987"))
        assertTrue(client.discordRole(uuid, "123"))

        val (byName, byId) = api.requests
        assertEquals("/api/v2/subscribed", byName.path)
        assertEquals("minecraft=$uuid&platform=discord&data=Mod+Team&server=987", byName.query)
        assertEquals("minecraft=$uuid&platform=discord&data=123", byId.query)
    }

    @Test
    fun `basic credentials are encoded`() {
        api.handler = { FakeApi.Reply(200, """{"result":false}""") }
        assertFalse(api.client(Credentials.Application("app", "shh")).following(uuid, Platform.GOOGLE))
        val expected = "Basic " + Base64.getEncoder().encodeToString("app:shh".toByteArray())
        assertEquals(expected, api.requests.single().headers["Authorization"])
    }

    @Test
    fun `404 from a membership check means not a member`() {
        api.handler = { error(404) }
        assertFalse(api.client().subscribed(uuid, Platform.PATREON, "Gold"))
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403])
    fun `configuration errors`(status: Int) {
        api.handler = { error(status) }
        val e = assertFailsWith<ConfigurationException> { api.client().discordPresent(uuid, "1") }
        assertEquals(status, e.status)
        assertTrue(e.message!!.contains("nope $status"))
    }

    @ParameterizedTest
    @ValueSource(ints = [409, 429, 500, 502])
    fun `temporary errors`(status: Int) {
        api.handler = { error(status) }
        val e = assertFailsWith<UnavailableException> { api.client().following(uuid, Platform.TWITCH) }
        assertEquals(status, e.status)
    }

    @Test
    fun `network failure is unavailable`() {
        val client = MinecraftAuth(MinecraftAuthConfig("Test/1.0", Credentials.None, "http://127.0.0.1:1"))
        assertFailsWith<UnavailableException> { client.lookup(uuid) }
    }

    @Test
    fun `grant request lifecycle`() {
        var polls = 0
        api.handler = { request ->
            when {
                request.method == "POST" -> FakeApi.Reply(
                    201,
                    """{"id":"req1","code":"ABCD-EFGH","url":"https://minecraftauth.me/grant?code=ABCD-EFGH",
                       "verificationUrl":"https://minecraftauth.me/grant","scopes":["minecraft","discord"],
                       "persist":false,"expiresAt":${System.currentTimeMillis() + 600_000},"interval":1}""",
                )
                polls++ == 0 -> FakeApi.Reply(200, """{"status":"pending","expiresAt":${System.currentTimeMillis() + 600_000}}""")
                else -> FakeApi.Reply(200, """{"status":"accepted","expiresAt":1,"token":"a.b.c"}""")
            }
        }
        val client = api.client(Credentials.Application("app", "secret"))
        val request = client.createGrantRequest(listOf(Platform.DISCORD), persist = false)
        assertEquals("ABCD-EFGH", request.code)
        assertEquals(listOf("minecraft", "discord"), request.scopes)
        assertEquals("""{"scopes":["discord"],"persist":false}""", api.requests.first().body)

        val status = client.awaitGrant(request)
        assertEquals(GrantStatus.ACCEPTED, status.status)
        assertEquals("a.b.c", status.token)
        assertEquals("/api/v2/grant-requests/req1", api.requests.last().path)
    }

    @Test
    fun `lookup escapes the identifier`() {
        api.handler = { FakeApi.Reply(200, "{}") }
        val result = api.client().lookup(Platform.PATREON, "a b/c?d#e%f+g")!!

        assertEquals("/api/v2/lookup/patreon/a%20b%2Fc%3Fd%23e%25f%2Bg", api.requests.single().path)
        assertNull(api.requests.single().query)
        // a response without accounts is an empty result, not "not found"
        assertTrue(result.accounts.isEmpty())
    }

    @Test
    fun `query values are escaped`() {
        api.handler = { FakeApi.Reply(200, """{"result":true}""") }
        api.client().subscribed(uuid, Platform.PATREON, "Gold & Silver=1")
        assertEquals("minecraft=$uuid&platform=patreon&data=Gold+%26+Silver%3D1", api.requests.single().query)
    }

    @Test
    fun `blank data is left out`() {
        api.handler = { FakeApi.Reply(200, """{"result":true}""") }
        api.client().subscribed(uuid, Platform.TWITCH, " ")
        api.client().discordRole(uuid, "123", "")
        assertEquals("minecraft=$uuid&platform=twitch", api.requests[0].query)
        assertEquals("minecraft=$uuid&platform=discord&data=123", api.requests[1].query)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "	"])
    fun `a blank User-Agent is rejected`(userAgent: String) {
        val e = assertFailsWith<IllegalArgumentException> { MinecraftAuthConfig(userAgent) }
        assertTrue(e.message!!.contains("User-Agent"))
    }

    @Test
    fun `argument checks happen before any request`() {
        val client = api.client()
        assertFailsWith<IllegalArgumentException> { client.following(uuid, Platform.PATREON) }
        assertFailsWith<IllegalArgumentException> { client.subscribed(uuid, Platform.MINECRAFT) }
        assertFailsWith<IllegalArgumentException> { client.discordRole(uuid, " ") }
        assertTrue(api.requests.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", """{"result":null}""", """{"result":"maybe"}""", """{"other":true}"""])
    fun `a membership response without a result is unavailable`(body: String) {
        api.handler = { FakeApi.Reply(200, body) }
        val e = assertFailsWith<UnavailableException> { api.client().following(uuid, Platform.TWITCH) }
        assertEquals(200, e.status)
        assertTrue(e.message!!.contains("without a result"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "not json", "[true]", "<html>"])
    fun `invalid json is unavailable`(body: String) {
        api.handler = { FakeApi.Reply(200, body) }
        assertFailsWith<UnavailableException> { api.client().discordPresent(uuid, "1") }
    }

    @Test
    fun `awaitGrant stops polling when the request would expire before the next poll`() {
        val expiresAt = System.currentTimeMillis() + 1_500
        api.handler = { FakeApi.Reply(200, """{"status":"pending","expiresAt":$expiresAt}""") }
        val request = grantRequest(interval = 2, expiresAt = expiresAt)

        val started = System.nanoTime()
        val status = api.client(Credentials.Application("app", "secret")).awaitGrant(request)

        assertEquals(GrantStatus.EXPIRED, status.status)
        assertNull(status.token)
        assertEquals(1, api.requests.size)
        // it gave up right away instead of sleeping past the deadline
        assertTrue(System.nanoTime() - started < 1_000_000_000L)
    }

    @Test
    fun `awaitGrant polls at the interval until the player answers`() {
        var polls = 0
        api.handler = {
            if (++polls < 3) FakeApi.Reply(200, """{"status":"pending","expiresAt":${System.currentTimeMillis() + 60_000}}""")
            else FakeApi.Reply(200, """{"status":"denied","expiresAt":1}""")
        }
        val started = System.nanoTime()
        val status = api.client(Credentials.Application("app", "secret")).awaitGrant(grantRequest(interval = 1))

        assertEquals(GrantStatus.DENIED, status.status)
        assertEquals(3, api.requests.size)
        assertTrue(api.requests.all { it.method == "GET" && it.path == "/api/v2/grant-requests/req%2F1" })
        // two sleeps of one second between the three polls
        assertTrue(System.nanoTime() - started >= 2_000_000_000L)
    }

    @Test
    fun `awaitGrant treats an interval below one second as one second`() {
        var polls = 0
        api.handler = {
            if (++polls < 2) FakeApi.Reply(200, """{"status":"pending","expiresAt":${System.currentTimeMillis() + 60_000}}""")
            else FakeApi.Reply(200, """{"status":"accepted","expiresAt":1,"token":"t"}""")
        }
        val started = System.nanoTime()
        assertEquals("t", api.client().awaitGrant(grantRequest(interval = 0)).token)
        assertTrue(System.nanoTime() - started >= 1_000_000_000L)
    }

    @Test
    fun `awaitGrant fails once the request is gone`() {
        api.handler = { error(404) }
        assertFailsWith<NotFoundException> { api.client().awaitGrant(grantRequest(interval = 1)) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["pending", "accepted", "denied", "canceled", "cancelled", "expired", "something-new"])
    fun `grant statuses`(raw: String) {
        api.handler = { FakeApi.Reply(200, """{"status":"$raw","expiresAt":5}""") }
        val expected = when (raw) {
            "pending" -> GrantStatus.PENDING
            "accepted" -> GrantStatus.ACCEPTED
            "denied", "canceled", "cancelled" -> GrantStatus.DENIED
            else -> GrantStatus.EXPIRED
        }
        val status = api.client().getGrantRequest("x")
        assertEquals(expected, status.status)
        assertEquals(5, status.expiresAt)
    }

    private fun grantRequest(interval: Int, expiresAt: Long = System.currentTimeMillis() + 600_000) =
        GrantRequest("req/1", "ABCD-EFGH", "https://minecraftauth.me/grant?code=ABCD-EFGH", "https://minecraftauth.me/grant",
            listOf("minecraft"), true, expiresAt, interval)

    @Test
    fun `platform ids`() {
        assertEquals(Platform.GOOGLE, Platform.fromId("YouTube"))
        assertEquals(Platform.TWITCH, Platform.fromId("TWITCH"))
        assertNull(Platform.fromId("glimpse"))
    }

    @Test
    fun `uuid parsing accepts undashed`() {
        assertEquals(uuid, parseUuid("069a79f444e94726a5befca90e38aaf5"))
        assertNull(parseUuid("nope"))
    }
}
