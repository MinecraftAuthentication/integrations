package me.minecraftauth.minecraft

/** The outcome of [Gatekeeper.check]. */
class GatekeeperResult private constructor(
    val type: Type,
    /** For [Type.DENIED]: the kick message, with `&` color codes already translated to `§`. */
    val message: String?,
    /** For [Type.DENIED]: why, for the log (e.g. "none of the conditions are met"). */
    val reason: String? = null,
) {
    enum class Type {
        /** A condition passed. */
        ALLOWED,

        /** The player is on the bypass list, or an admin with admin bypass enabled. */
        BYPASSED,

        /** No condition passed, or the API failed and `on-api-error` is `deny`. */
        DENIED,

        /** No conditions are configured for this realm (or no credentials are set). */
        NOT_ENABLED,
    }

    val denied: Boolean get() = type == Type.DENIED

    override fun toString(): String = if (message == null) type.name else "$type($message)"

    companion object {
        @JvmField val ALLOWED = GatekeeperResult(Type.ALLOWED, null)
        @JvmField val BYPASSED = GatekeeperResult(Type.BYPASSED, null)
        @JvmField val NOT_ENABLED = GatekeeperResult(Type.NOT_ENABLED, null)

        @JvmStatic
        @JvmOverloads
        fun denied(message: String, reason: String? = null) = GatekeeperResult(Type.DENIED, Text.colorize(message), reason)
    }
}

object Text {
    /** Translates `&` color codes (`&c`, `&l`, ...) into `§` codes that every platform's legacy text understands. */
    @JvmStatic
    fun colorize(message: String): String {
        val chars = message.toCharArray()
        for (i in 0 until chars.size - 1) {
            if (chars[i] == '&' && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(chars[i + 1]) >= 0) {
                chars[i] = '§'
                chars[i + 1] = chars[i + 1].lowercaseChar()
            }
        }
        return String(chars)
    }
}
