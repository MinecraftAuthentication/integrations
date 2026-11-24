package runtimetest

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * A minimal offline-mode Minecraft client, just enough to drive a server through `PlayerList.canPlayerLogin`:
 * a status ping (to learn the server's protocol number) and a login that stops as soon as the server
 * either kicks the player or accepts them. Needs `online-mode=false` and `network-compression-threshold=-1`.
 */
class LoginProbe(private val host: String, private val port: Int, private val timeoutMillis: Int = 60_000) {

    sealed class Outcome {
        /** Login Success (or Set Compression, which vanilla only sends after `canPlayerLogin` passed). */
        data object Accepted : Outcome()
        /** Disconnect during login; [reason] is the JSON text component. */
        data class Kicked(val reason: String) : Outcome()
    }

    /** The server's protocol number, from a status ping. */
    fun protocol(): Int = connect { socket, input ->
        val out = socket.getOutputStream()
        out.write(packet(0x00) { varInt(-1); string(host); short(port); varInt(1) })
        out.write(packet(0x00) {})
        out.flush()
        val (id, body) = readPacket(input)
        check(id == 0x00) { "Unexpected status response packet 0x${id.toString(16)}" }
        val json = body.string()
        Regex("\"protocol\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toInt()
            ?: error("No protocol number in status response: $json")
    }

    fun login(name: String, protocol: Int): Outcome = connect { socket, input ->
        val out = socket.getOutputStream()
        val uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(StandardCharsets.UTF_8))
        out.write(packet(0x00) { varInt(protocol); string(host); short(port); varInt(2) })
        out.write(packet(0x00) {
            string(name)
            when {
                protocol < 759 -> {}                                   // 1.16 - 1.18.2: name only
                protocol == 759 -> bool(false)                         // 1.19: no signature data
                protocol == 760 -> { bool(false); bool(true); uuid(uuid) } // 1.19.1/2
                protocol < 764 -> { bool(true); uuid(uuid) }           // 1.19.3 - 1.20.1
                else -> uuid(uuid)                                     // 1.20.2+
            }
        })
        out.flush()
        awaitLoginOutcome(input, out)
    }

    private fun awaitLoginOutcome(input: DataInputStream, out: OutputStream): Outcome {
        while (true) {
            val (id, body) = readPacket(input)
            when (id) {
                0x00 -> return Outcome.Kicked(body.string())
                0x01 -> error("Server asked for encryption; is online-mode still on?")
                0x02, 0x03 -> return Outcome.Accepted
                0x04 -> { // Login Plugin Request (Forge / Fabric login queries): answer "not understood"
                    val messageId = body.varInt()
                    out.write(packet(0x02) { varInt(messageId); bool(false) })
                    out.flush()
                }
                else -> {} // cookies etc.: ignore
            }
        }
    }

    private fun <T> connect(block: (Socket, DataInputStream) -> T): T = Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), timeoutMillis)
        socket.soTimeout = timeoutMillis
        block(socket, DataInputStream(socket.getInputStream().buffered()))
    }

    private class Writer : ByteArrayOutputStream() {
        fun varInt(value: Int) {
            var v = value
            while (true) {
                if (v and 0x7F.inv() == 0) { write(v); return }
                write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
        }
        fun string(s: String) { val b = s.toByteArray(StandardCharsets.UTF_8); varInt(b.size); write(b) }
        fun short(v: Int) { write((v shr 8) and 0xFF); write(v and 0xFF) }
        fun bool(v: Boolean) = write(if (v) 1 else 0)
        fun uuid(u: UUID) { long(u.mostSignificantBits); long(u.leastSignificantBits) }
        private fun long(v: Long) { for (i in 7 downTo 0) write(((v shr (i * 8)) and 0xFF).toInt()) }
    }

    private fun packet(id: Int, body: Writer.() -> Unit): ByteArray {
        val payload = Writer().apply { varInt(id); body() }.toByteArray()
        return Writer().apply { varInt(payload.size); write(payload) }.toByteArray()
    }

    private class Reader(private val bytes: ByteArray) {
        private var pos = 0
        fun varInt(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = bytes[pos++].toInt()
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                check(shift < 35) { "VarInt too long" }
            }
        }
        fun string(): String { val len = varInt(); return String(bytes, pos, len, StandardCharsets.UTF_8).also { pos += len } }
    }

    private fun readPacket(input: DataInputStream): Pair<Int, Reader> {
        val length = readVarInt(input)
        val data = ByteArray(length)
        input.readFully(data)
        val reader = Reader(data)
        return reader.varInt() to reader
    }

    private fun readVarInt(input: InputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Server closed the connection")
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            check(shift < 35) { "VarInt too long" }
        }
    }
}
