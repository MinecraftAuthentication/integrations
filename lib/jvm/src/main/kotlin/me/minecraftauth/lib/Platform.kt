package me.minecraftauth.lib

/**
 * A platform that a MinecraftAuth.me identity can link an account on.
 *
 * @property id the identifier used by the API (always lowercase)
 * @property displayName a human friendly name
 */
enum class Platform(val id: String, val displayName: String) {
    MINECRAFT("minecraft", "Minecraft"),
    DISCORD("discord", "Discord"),
    TWITCH("twitch", "Twitch"),
    PATREON("patreon", "Patreon"),

    /** YouTube. The v2 API calls this platform `google`. */
    GOOGLE("google", "YouTube");

    override fun toString(): String = id

    companion object {
        /** Looks a platform up by its API id, ignoring case. `youtube` is accepted as an alias of [GOOGLE]. */
        @JvmStatic
        fun fromId(id: String): Platform? {
            val normalized = id.trim().lowercase()
            if (normalized == "youtube") return GOOGLE
            return entries.firstOrNull { it.id == normalized }
        }
    }
}
