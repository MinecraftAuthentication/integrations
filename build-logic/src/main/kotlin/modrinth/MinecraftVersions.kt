package modrinth

import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.net.HttpURLConnection
import java.net.URI

/**
 * Every Minecraft *release* Modrinth knows that falls in [range], oldest first, for a Modrinth upload's game versions.
 *
 * [range] uses the syntax the mod metadata already uses, so one value can feed both:
 *  - Fabric: `*`, `>=1.19 <1.20`, `>=26.3`, `1.20.1`
 *  - Maven (Forge / NeoForge): `[1.16.5,1.17)`, `[26.3,)`, `[1.20.1]`
 *
 * The list is fetched from the Modrinth API lazily, i.e. only when an upload actually runs.
 */
fun Project.minecraftReleases(range: String): Provider<List<String>> =
    providers.of(ModrinthGameVersions::class.java) { parameters.range.set(range) }

abstract class ModrinthGameVersions : ValueSource<List<String>, ModrinthGameVersions.Parameters> {
    interface Parameters : ValueSourceParameters {
        val range: Property<String>
    }

    override fun obtain(): List<String> {
        val range = MinecraftVersionRange.parse(parameters.range.get())
        val connection = URI("https://api.modrinth.com/v2/tag/game_version").toURL().openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "MinecraftAuth-integrations-build")
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        val body = try {
            if (connection.responseCode != 200) error("Modrinth game versions request failed: HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        // [{"version":"1.21.1","version_type":"release",...}, ...] -- avoids a JSON dependency in build-logic
        val releases = Regex("""\{[^{}]*}""").findAll(body).map { it.value }
            .filter { Regex(""""version_type"\s*:\s*"release"""").containsMatchIn(it) }
            .mapNotNull { Regex(""""version"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
            .toList()
        val matches = releases.filter(range::contains).sortedWith(MinecraftVersionRange.COMPARATOR)
        if (matches.isEmpty()) error("No Minecraft release on Modrinth matches '${parameters.range.get()}'")
        return matches
    }
}

/** A version interval; `null` bounds are open. */
data class MinecraftVersionRange(
    val min: String?,
    val minInclusive: Boolean,
    val max: String?,
    val maxInclusive: Boolean,
) {
    fun contains(version: String): Boolean {
        if (min != null) {
            val c = COMPARATOR.compare(version, min)
            if (c < 0 || (c == 0 && !minInclusive)) return false
        }
        if (max != null) {
            val c = COMPARATOR.compare(version, max)
            if (c > 0 || (c == 0 && !maxInclusive)) return false
        }
        return true
    }

    companion object {
        /** Numeric, segment by segment: 1.9 < 1.16.5 < 1.21 < 26.1 < 26.1.1. */
        val COMPARATOR = Comparator<String> { a, b ->
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            (0 until maxOf(x.size, y.size)).asSequence()
                .map { (x.getOrNull(it) ?: 0).compareTo(y.getOrNull(it) ?: 0) }
                .firstOrNull { it != 0 } ?: 0
        }

        fun parse(spec: String): MinecraftVersionRange {
            val s = spec.trim()
            if (s.isEmpty() || s == "*") return MinecraftVersionRange(null, true, null, true)
            if (s.first() in "[(") return parseMaven(s)
            var range = MinecraftVersionRange(null, true, null, true)
            for (part in s.split(Regex("\\s+"))) {
                val op = Regex("^(>=|<=|>|<|=)?(.+)$").matchEntire(part) ?: error("Bad version range '$spec'")
                val v = op.groupValues[2]
                range = when (op.groupValues[1]) {
                    ">=" -> range.copy(min = v, minInclusive = true)
                    ">" -> range.copy(min = v, minInclusive = false)
                    "<=" -> range.copy(max = v, maxInclusive = true)
                    "<" -> range.copy(max = v, maxInclusive = false)
                    else -> MinecraftVersionRange(v, true, v, true)
                }
            }
            return range
        }

        private fun parseMaven(s: String): MinecraftVersionRange {
            if (s.last() !in "])") error("Bad version range '$s'")
            val bounds = s.substring(1, s.length - 1).split(',').map(String::trim)
            return when (bounds.size) {
                1 -> MinecraftVersionRange(bounds[0], true, bounds[0], true)
                2 -> MinecraftVersionRange(
                    bounds[0].ifEmpty { null }, s.first() == '[',
                    bounds[1].ifEmpty { null }, s.last() == ']',
                )
                else -> error("Bad version range '$s'")
            }
        }
    }
}
