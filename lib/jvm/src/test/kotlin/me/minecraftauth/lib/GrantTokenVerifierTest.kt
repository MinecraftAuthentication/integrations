package me.minecraftauth.lib

import me.minecraftauth.lib.grant.GrantTokenVerifier
import me.minecraftauth.lib.grant.KeySource
import me.minecraftauth.lib.grant.PublicKeys
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GrantTokenVerifierTest {

    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val now = 1_800_000_000L

    private fun b64(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(StandardCharsets.UTF_8))

    private fun sign(payload: String, alg: String = "RS512", key: KeyPair = keys): String {
        val signingInput = b64("""{"alg":"$alg","typ":"JWT"}""") + "." + b64(payload)
        val signature = Signature.getInstance("SHA512withRSA").run {
            initSign(key.private)
            update(signingInput.toByteArray(StandardCharsets.US_ASCII))
            sign()
        }
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
    }

    private val payload = """{"sub":"1","iss":"MinecraftAuth.me","aud":"app","iat":$now,"exp":${now + 600},
        "minecraft":{"identifier":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch","avatar":null},
        "discord":{"identifier":"1234","name":"notch","avatar":"https://cdn/x.png"}}"""

    private fun verifier(maxAge: Long? = 600, audience: String? = null) =
        GrantTokenVerifier({ keys.public }, audience = audience, maxAgeSeconds = maxAge, nowSeconds = { now + 5 })

    @Test
    fun `valid token`() {
        val claims = verifier().verify(sign(payload))
        assertEquals("1", claims.subject)
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), claims.minecraftUuid)
        assertEquals("Notch", claims.minecraft!!.name)
        assertEquals("1234", claims.accounts[Platform.DISCORD]!!.identifier)
    }

    @Test
    fun `wrong key`() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertFailsWith<InvalidTokenException> { verifier().verify(sign(payload, key = other)) }
    }

    @Test
    fun `tampered payload`() {
        val parts = sign(payload).split('.')
        val forged = parts[0] + "." + b64(payload.replace("Notch", "Jeb")) + "." + parts[2]
        assertFailsWith<InvalidTokenException> { verifier().verify(forged) }
    }

    @Test
    fun `algorithm is pinned`() {
        val parts = sign(payload).split('.')
        val none = b64("""{"alg":"none"}""") + "." + parts[1] + "."
        assertFailsWith<InvalidTokenException> { verifier().verify(none) }
    }

    @Test
    fun `stale token rejected`() {
        val verifier = GrantTokenVerifier({ keys.public }, maxAgeSeconds = 600, nowSeconds = { now + 3600 })
        assertFailsWith<InvalidTokenException> { verifier.verify(sign(payload)) }
    }

    @Test
    fun `wrong issuer`() {
        assertFailsWith<InvalidTokenException> { verifier().verify(sign(payload.replace("MinecraftAuth.me", "evil"))) }
    }

    @Test
    fun `audience must match when configured`() {
        val claims = verifier(audience = "app").verify(sign(payload))
        assertEquals(listOf("app"), claims.audience)
        assertFailsWith<InvalidTokenException> {
            verifier(audience = "app").verify(sign(payload.replace("\"aud\":\"app\"", "\"aud\":\"other\"")))
        }
        // a token without aud was issued to nobody in particular, so it can't be for this application
        assertFailsWith<InvalidTokenException> {
            verifier(audience = "app").verify(sign(payload.replace("\"aud\":\"app\",", "")))
        }
        // without a configured audience, any aud is accepted
        verifier().verify(sign(payload.replace("\"aud\":\"app\"", "\"aud\":\"other\"")))
    }

    @Test
    fun `exp is required and enforced`() {
        assertFailsWith<InvalidTokenException> { verifier(maxAge = null).verify(sign(payload.replace(",\"exp\":${now + 600}", ""))) }
        val later = GrantTokenVerifier({ keys.public }, nowSeconds = { now + 600 + 61 })
        assertFailsWith<InvalidTokenException> { later.verify(sign(payload)) }
        assertEquals(now + 600, verifier(maxAge = null).verify(sign(payload)).expiresAt)
    }

    @Test
    fun `pem parsing supports spki and pkcs1`() {
        val spki = keys.public.encoded
        val spkiPem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(spki) + "\n-----END PUBLIC KEY-----\n"
        assertEquals(keys.public, PublicKeys.parsePem(spkiPem))

        // PKCS#1 RSAPublicKey is the BIT STRING content at the end of the SPKI structure (2048-bit key: 270 bytes)
        val rsa = keys.public as RSAPublicKey
        val pkcs1 = spki.copyOfRange(spki.size - 270, spki.size)
        val pkcs1Pem = "-----BEGIN RSA PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(pkcs1) + "\n-----END RSA PUBLIC KEY-----"
        val parsed = PublicKeys.parsePem(pkcs1Pem) as RSAPublicKey
        assertEquals(rsa.modulus, parsed.modulus)
        assertEquals(rsa.publicExponent, parsed.publicExponent)
    }

    private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** A KeySource whose fetches return [served] and are counted; the clock is [clock] milliseconds. */
    private class Rotating(var served: () -> PublicKey) {
        var fetches = 0
        var clock = 0L
        val keys = KeySource({ fetches++; served() }, refreshIntervalMillis = 60_000, clockMillis = { clock })
    }

    private fun verifier(keys: KeySource) = GrantTokenVerifier(keys, maxAgeSeconds = 600, nowSeconds = { now + 5 })

    @Test
    fun `key source fetches once and caches`() {
        val source = Rotating { keys.public }
        val verifier = verifier(source.keys)
        verifier.verify(sign(payload))
        verifier.verify(sign(payload))
        assertEquals(1, source.fetches)
    }

    @Test
    fun `rotated key is fetched again`() {
        val source = Rotating { keys.public }
        val verifier = verifier(source.keys)
        verifier.verify(sign(payload))

        val rotated = newKeyPair()
        source.served = { rotated.public }
        source.clock += 61_000
        assertEquals("1", verifier.verify(sign(payload, key = rotated)).subject)
        assertEquals(2, source.fetches)
        // the new key is cached now
        verifier.verify(sign(payload, key = rotated))
        assertEquals(2, source.fetches)
    }

    @Test
    fun `bad signatures refetch at most once per interval`() {
        val source = Rotating { keys.public }
        val verifier = verifier(source.keys)
        val forged = sign(payload, key = newKeyPair())

        assertFailsWith<InvalidTokenException> { verifier.verify(forged) }
        assertFailsWith<InvalidTokenException> { verifier.verify(forged) }
        assertEquals(1, source.fetches) // the first fetch was just now

        source.clock += 61_000
        assertFailsWith<InvalidTokenException> { verifier.verify(forged) }
        assertFailsWith<InvalidTokenException> { verifier.verify(forged) }
        assertEquals(2, source.fetches)
    }

    @Test
    fun `failed refresh keeps the cached key`() {
        val source = Rotating { keys.public }
        val verifier = verifier(source.keys)
        verifier.verify(sign(payload))

        source.served = { throw UnavailableException("down") }
        source.clock += 61_000
        assertFailsWith<InvalidTokenException> { verifier.verify(sign(payload, key = newKeyPair())) }
        assertEquals(2, source.fetches)
        verifier.verify(sign(payload))
    }

    @Test
    fun `failing to fetch the key is not an invalid token`() {
        val source = Rotating { throw UnavailableException("down", 503) }
        assertFailsWith<UnavailableException> { verifier(source.keys).verify(sign(payload)) }
    }

    @Test
    fun `key source fetches from the api`() {
        FakeApi().use { api ->
            val pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(keys.public.encoded) + "\n-----END PUBLIC KEY-----"
            api.handler = { FakeApi.Reply(200, pem, "text/plain") }
            val verifier = verifier(KeySource(api.client(Credentials.None)))
            verifier.verify(sign(payload))
            verifier.verify(sign(payload))
            assertEquals(listOf("/rsa"), api.requests.map { it.path })
        }
    }

    @Test
    fun `fetch public key from api`() {
        FakeApi().use { api ->
            val pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(keys.public.encoded) + "\n-----END PUBLIC KEY-----"
            api.handler = { FakeApi.Reply(200, pem, "text/plain") }
            assertEquals(keys.public, api.client(Credentials.None).fetchPublicKey())
            assertEquals("/rsa", api.requests.single().path)
        }
    }
}
