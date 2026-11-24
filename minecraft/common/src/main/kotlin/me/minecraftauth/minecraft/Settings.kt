package me.minecraftauth.minecraft

import dev.dejvokep.boostedyaml.YamlDocument
import dev.dejvokep.boostedyaml.block.implementation.Section
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings
import java.io.File

enum class ApiErrorPolicy { DENY, ALLOW }

/** Gatekeeping rules for one realm: the whole server, or one backend server behind a proxy. */
data class RealmSettings(
    val name: String?,
    val conditions: List<String>,
    val bypass: List<String>,
    val deniedMessage: String,
    val errorMessage: String,
)

/** Everything read from `MinecraftAuth.yml`. */
data class Settings(
    val serverToken: String?,
    val cacheSeconds: Long,
    val bypassAdmins: Boolean,
    val onApiError: ApiErrorPolicy,
    val global: RealmSettings,
    val servers: Map<String, RealmSettings>,
) {
    companion object {
        const val FILE_NAME = "MinecraftAuth.yml"

        /** Proxy-only options, appended to the end of the default file's `gatekeeper:` block on proxies. */
        private const val PROXY_FILE_NAME = "MinecraftAuth-proxy.yml"

        /**
         * Loads (creating it from the bundled defaults if missing) `MinecraftAuth.yml` in [dataDir].
         * Only a [proxy] gets the proxy options (`gatekeeper.servers`) in its defaults and reads them.
         */
        @JvmStatic
        @JvmOverloads
        fun load(dataDir: File, proxy: Boolean = false): Settings {
            dataDir.mkdirs()
            var defaults = resource(FILE_NAME)
            if (proxy) defaults += resource(PROXY_FILE_NAME)
            val document = defaults.byteInputStream().use {
                YamlDocument.create(
                    File(dataDir, FILE_NAME),
                    it,
                    GeneralSettings.builder().setUseDefaults(true).build(),
                    LoaderSettings.builder().setAutoUpdate(false).setCreateFileIfAbsent(true).build(),
                    DumperSettings.DEFAULT,
                    UpdaterSettings.DEFAULT,
                )
            }
            return from(document, proxy)
        }

        private fun resource(name: String): String =
            (Settings::class.java.getResourceAsStream("/$name") ?: error("Bundled default $name is missing"))
                .use { it.readBytes().toString(Charsets.UTF_8) }

        @JvmStatic
        @JvmOverloads
        fun from(root: Section, proxy: Boolean = false): Settings {
            val global = realm(root.getSection("gatekeeper"), null, null)
            val servers = LinkedHashMap<String, RealmSettings>()
            if (proxy) root.getOptionalSection("gatekeeper.servers").ifPresent { section ->
                for (key in section.getRoutesAsStrings(false)) {
                    val child = section.getSection(key) ?: continue
                    servers[key] = realm(child, key, global)
                }
            }
            return Settings(
                serverToken = root.getString("api.server-token", "").trim().ifEmpty { null },
                cacheSeconds = root.getLong("api.cache-seconds", 60L).coerceAtLeast(0),
                bypassAdmins = root.getBoolean("gatekeeper.bypass-admins", true),
                onApiError = if (root.getString("gatekeeper.on-api-error", "deny").trim().equals("allow", true)) {
                    ApiErrorPolicy.ALLOW
                } else {
                    ApiErrorPolicy.DENY
                },
                global = global,
                servers = servers,
            )
        }

        private fun realm(section: Section?, name: String?, parent: RealmSettings?): RealmSettings {
            fun list(route: String): List<String>? =
                if (section?.contains(route) == true) section.getStringList(route).map { it.trim() }.filter { it.isNotEmpty() } else null

            return RealmSettings(
                name = name,
                conditions = list("conditions") ?: emptyList(),
                bypass = (parent?.bypass ?: emptyList()) + (list("bypass") ?: emptyList()),
                deniedMessage = section?.getString("messages.denied", null) ?: parent?.deniedMessage
                    ?: "&cYou don't meet the requirements to join this server.",
                errorMessage = section?.getString("messages.error", null) ?: parent?.errorMessage
                    ?: "&cWe couldn't check your linked accounts right now. Please try again in a minute.",
            )
        }
    }
}
