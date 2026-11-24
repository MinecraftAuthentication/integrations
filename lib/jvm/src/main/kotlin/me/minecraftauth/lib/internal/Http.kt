package me.minecraftauth.lib.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.minecraftauth.lib.ConfigurationException
import me.minecraftauth.lib.MinecraftAuthConfig
import me.minecraftauth.lib.NotFoundException
import me.minecraftauth.lib.RateLimitedException
import me.minecraftauth.lib.UnavailableException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

internal class Http(private val config: MinecraftAuthConfig) {

    /**
     * @param retryAfterMillis a 429's `Retry-After`, in milliseconds
     */
    class Response(val status: Int, val body: String, private val path: String, val retryAfterMillis: Long? = null) {

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
                // a 429 without Retry-After is a cap, like on unanswered access requests, not a rate limit
                429 -> if (retryAfterMillis != null) RateLimitedException(message, retryAfterMillis) else UnavailableException(message, status)
                else -> UnavailableException(message, status)
            }
        }
    }

    /** Until when (epoch millis) the API asked us not to send calls, per [limitKey] */
    private val cooldowns = ConcurrentHashMap<String, Long>()

    fun get(path: String, query: Map<String, String> = emptyMap()): Response = request("GET", path, query, null)

    fun post(path: String, body: String): Response = request("POST", path, emptyMap(), body)

    /**
     * Sends a call, waiting out rate limits (429 with `Retry-After`) for up to
     * [MinecraftAuthConfig.maxRateLimitWaitMillis] in all. A call made while an earlier one is still asked to wait
     * isn't sent until then, so a rate limited client doesn't keep hitting the API.
     *
     * @return the response; a 429 when waiting it out would have taken too long
     * @throws RateLimitedException if it would have to wait too long before it could even be sent
     */
    private fun request(method: String, path: String, query: Map<String, String>, body: String?): Response {
        val key = limitKey(method, path)
        var waited = 0L
        while (true) {
            val wait = cooldownMillis(key)
            if (wait > 0) {
                if (waited + wait > config.maxRateLimitWaitMillis) {
                    throw RateLimitedException("MinecraftAuth.me is rate limiting this client; retry in ${wait}ms ($method $path)", wait)
                }
                Thread.sleep(wait)
                waited += wait
            }

            val response = send(method, path, query, body)
            val retryAfter = response.retryAfterMillis
            if (response.status != 429 || retryAfter == null) return response
            cooldowns.merge(key, System.currentTimeMillis() + retryAfter, ::maxOf)
            if (waited + retryAfter > config.maxRateLimitWaitMillis) return response
        }
    }

    /** How long calls under [key] must still wait: the API's limit on everything, and on [key] in particular */
    private fun cooldownMillis(key: String): Long {
        val until = maxOf(cooldowns[ALL] ?: 0L, cooldowns[key] ?: 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0)
    }

    private fun send(method: String, path: String, query: Map<String, String>, body: String?): Response {
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
            val retryAfter = if (status == 429) parseRetryAfter(connection.getHeaderField("Retry-After")) else null
            connection.disconnect()
            return Response(status, text, path, retryAfter)
        } catch (e: IOException) {
            throw UnavailableException("Couldn't reach MinecraftAuth.me ($method $path): ${e.message}", null, e)
        }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private const val ALL = "*"
        private const val GRANT_REQUESTS = "/api/v2/grant-requests"

        /**
         * Which of the API's limits a call counts against: starting access requests has a limit of its own, on top
         * of the one on every call
         */
        fun limitKey(method: String, path: String): String =
            if (method == "POST" && path == GRANT_REQUESTS) "POST $GRANT_REQUESTS" else ALL

        /** A `Retry-After` header in milliseconds: delay-seconds or an HTTP date. Null if missing or malformed */
        fun parseRetryAfter(value: String?, now: Long = System.currentTimeMillis()): Long? {
            val trimmed = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
            trimmed.toLongOrNull()?.let { return if (it >= 0) it * 1000 else null }
            return try {
                (ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0)
            } catch (e: Exception) {
                null
            }
        }

        fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")

        fun encodePath(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }
}
