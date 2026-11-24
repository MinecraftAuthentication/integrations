package me.minecraftauth.lib

import me.minecraftauth.lib.internal.Http
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RateLimitTest {

    private val api = FakeApi()
    private val uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")

    @AfterEach
    fun close() = api.close()

    private fun limited(seconds: Int) =
        FakeApi.Reply(429, """{"error":"Rate limit exceeded","status":429}""", headers = mapOf("Retry-After" to seconds.toString()))

    @Test
    fun `waits out a short rate limit and retries`() {
        val calls = AtomicInteger()
        api.handler = { if (calls.getAndIncrement() == 0) limited(1) else FakeApi.Reply(200, """{"result":true}""") }

        val started = System.currentTimeMillis()
        assertTrue(api.client().following(uuid, Platform.TWITCH))
        assertEquals(2, api.requests.size)
        assertTrue(System.currentTimeMillis() - started >= 900, "retried before Retry-After")
    }

    @Test
    fun `a long rate limit throws and holds back later calls`() {
        api.handler = { limited(60) }
        val client = api.client()

        val first = assertFailsWith<RateLimitedException> { client.following(uuid, Platform.TWITCH) }
        assertEquals(429, first.status)
        assertEquals(60_000, first.retryAfterMillis)
        assertEquals(1, api.requests.size)

        // not sent at all while the limit lasts
        val second = assertFailsWith<RateLimitedException> { client.lookup(uuid) }
        assertTrue(second.retryAfterMillis in 1..60_000)
        assertEquals(1, api.requests.size)
    }

    @Test
    fun `zero max wait never waits`() {
        api.handler = { limited(1) }
        assertFailsWith<RateLimitedException> { api.client(maxRateLimitWaitMillis = 0).following(uuid, Platform.TWITCH) }
        assertEquals(1, api.requests.size)
    }

    @Test
    fun `a 429 without Retry-After isn't a rate limit and isn't retried`() {
        api.handler = { error(429) }
        val e = assertFailsWith<UnavailableException> { api.client().createGrantRequest() }
        assertFalse(e is RateLimitedException)
        assertEquals(1, api.requests.size)
    }

    @Test
    fun `the access request limit doesn't hold back other calls`() {
        api.handler = { if (it.method == "POST") limited(60) else FakeApi.Reply(200, """{"result":true}""") }
        val client = api.client()

        assertFailsWith<RateLimitedException> { client.createGrantRequest() }
        assertFailsWith<RateLimitedException> { client.createGrantRequest() }
        assertTrue(client.following(uuid, Platform.TWITCH))
        assertEquals(listOf("POST", "GET"), api.requests.map { it.method })
    }

    @Test
    fun `Retry-After parsing`() {
        assertEquals(3_000, Http.parseRetryAfter(" 3 "))
        assertEquals(0, Http.parseRetryAfter("0"))
        assertEquals(30_000, Http.parseRetryAfter("Wed, 21 Oct 2015 07:28:30 GMT", now = 1445412480000))
        assertEquals(0, Http.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT", now = 1445412510000))
        assertNull(Http.parseRetryAfter(null))
        assertNull(Http.parseRetryAfter("-1"))
        assertNull(Http.parseRetryAfter("soon"))
    }

    @Test
    fun `rate limited is temporary`() {
        assertIs<UnavailableException>(RateLimitedException("x", 1))
    }
}
