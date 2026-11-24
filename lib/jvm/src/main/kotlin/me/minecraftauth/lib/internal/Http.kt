package me.minecraftauth.lib.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.minecraftauth.lib.ConfigurationException
import me.minecraftauth.lib.MinecraftAuthConfig
import me.minecraftauth.lib.NotFoundException
import me.minecraftauth.lib.UnavailableException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class Http(private val config: MinecraftAuthConfig) {

    class Response(val status: Int, val body: String, private val path: String) {

        fun expectJson(): JsonObject {
            if (status !in 200..299) throwForStatus()
            return try {
                json.parseToJsonElement(body) as JsonObject
            } catch (e: Exception) {
                throw UnavailableException("MinecraftAuth.me returned invalid JSON for $path", status, e)
            }
        }

        fun errorMessage(): String {
            val message = try {
                (json.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull
            } catch (e: Exception) {
                null
            }
            return "MinecraftAuth.me $path returned HTTP $status" + (message?.let { ": $it" } ?: "")
        }

        fun throwForStatus(): Nothing {
            val message = errorMessage()
            throw when (status) {
                400, 401, 403 -> ConfigurationException(message, status)
                404 -> NotFoundException(message)
                else -> UnavailableException(message, status)
            }
        }
    }

    fun get(path: String, query: Map<String, String> = emptyMap()): Response = request("GET", path, query, null)

    fun post(path: String, body: String): Response = request("POST", path, emptyMap(), body)

    private fun request(method: String, path: String, query: Map<String, String>, body: String?): Response {
        val url = buildString {
            append(config.baseUrl.trimEnd('/'))
            append(path)
            if (query.isNotEmpty()) {
                append('?')
                append(query.entries.joinToString("&") { (k, v) -> encodeQuery(k) + "=" + encodeQuery(v) })
            }
        }
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = config.connectTimeoutMillis
            connection.readTimeout = config.readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", config.userAgent)
            connection.setRequestProperty("Accept", "application/json")
            config.credentials.authorizationHeader()?.let { connection.setRequestProperty("Authorization", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) } ?: ""
            connection.disconnect()
            return Response(status, text, path)
        } catch (e: IOException) {
            throw UnavailableException("Couldn't reach MinecraftAuth.me ($method $path): ${e.message}", null, e)
        }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")

        fun encodePath(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }
}
