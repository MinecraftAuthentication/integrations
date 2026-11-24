package me.minecraftauth.minecraft

import java.util.concurrent.ConcurrentHashMap

/** Bridges to the host platform's logger. */
interface PlatformLogger {
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)

    /** A logger that writes to stdout/stderr, for platforms without one. */
    object Console : PlatformLogger {
        override fun info(message: String) = println("[MinecraftAuth] $message")
        override fun warn(message: String) = System.err.println("[MinecraftAuth] WARN $message")
        override fun error(message: String, throwable: Throwable?) {
            System.err.println("[MinecraftAuth] ERROR $message")
            throwable?.printStackTrace()
        }
    }
}

/** Logs a given key at most once per [intervalMillis] so an API outage doesn't flood the console. */
internal class RateLimitedLog(private val logger: PlatformLogger, private val intervalMillis: Long = 60_000) {
    private val last = ConcurrentHashMap<String, Long>()

    fun warn(key: String, message: String) {
        val now = System.currentTimeMillis()
        val previous = last[key]
        if (previous == null || now - previous >= intervalMillis) {
            last[key] = now
            logger.warn(message)
        }
    }
}
