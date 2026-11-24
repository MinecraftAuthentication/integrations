package me.minecraftauth.lib.grant

import me.minecraftauth.lib.InvalidTokenException
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Parses the PEM-encoded RSA key published at `https://minecraftauth.me/rsa`. */
object PublicKeys {

    private val PEM = Regex("-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----")

    /** Accepts SPKI (`BEGIN PUBLIC KEY`) and PKCS#1 (`BEGIN RSA PUBLIC KEY`) PEM blocks. */
    @JvmStatic
    fun parsePem(pem: String): PublicKey {
        val match = PEM.find(pem) ?: throw InvalidTokenException("No PEM public key found")
        val type = match.groupValues[1]
        val der = Base64.getMimeDecoder().decode(match.groupValues[2].replace(Regex("\\s"), ""))
        val spki = when (type) {
            "PUBLIC KEY" -> der
            "RSA PUBLIC KEY" -> wrapPkcs1(der)
            else -> throw InvalidTokenException("Unsupported PEM block '$type'")
        }
        return try {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spki))
        } catch (e: Exception) {
            throw InvalidTokenException("Invalid RSA public key", e)
        }
    }

    // SubjectPublicKeyInfo ::= SEQUENCE { AlgorithmIdentifier(rsaEncryption, NULL), BIT STRING(pkcs1) }
    private val RSA_ALGORITHM_IDENTIFIER = byteArrayOf(
        0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00,
    )

    private fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
        val bitString = der(0x03, byteArrayOf(0) + pkcs1)
        return der(0x30, RSA_ALGORITHM_IDENTIFIER + bitString)
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val length = content.size
        when {
            length < 0x80 -> out.write(length)
            length < 0x100 -> { out.write(0x81); out.write(length) }
            length < 0x10000 -> { out.write(0x82); out.write(length shr 8); out.write(length and 0xff) }
            else -> { out.write(0x83); out.write(length shr 16); out.write((length shr 8) and 0xff); out.write(length and 0xff) }
        }
        out.write(content)
        return out.toByteArray()
    }
}
