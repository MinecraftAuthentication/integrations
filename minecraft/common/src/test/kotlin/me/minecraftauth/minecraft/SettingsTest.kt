package me.minecraftauth.minecraft

import me.minecraftauth.lib.Credentials
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTest {

    private fun load(dir: File, yaml: String, proxy: Boolean = false): Settings {
        File(dir, Settings.FILE_NAME).writeText(yaml)
        return Settings.load(dir, proxy)
    }

    private val defaults: Settings by lazy { Settings.load(createTempDir()) }

    private fun createTempDir(): File = kotlin.io.path.createTempDirectory("mcauth-defaults").toFile().also { it.deleteOnExit() }

    @Test
    fun `default config is written and parses`(@TempDir dir: File) {
        val settings = Settings.load(dir)
        assertTrue(File(dir, Settings.FILE_NAME).isFile)
        assertNull(settings.applicationId)
        assertNull(settings.applicationSecret)
        assertNull(settings.serverToken)
        assertFalse(settings.hasCredentials)
        assertFalse(File(dir, Settings.FILE_NAME).readText().contains("server-token"))
        assertEquals(60, settings.cacheSeconds)
        assertTrue(settings.bypassAdmins)
        assertEquals(ApiErrorPolicy.DENY, settings.onApiError)
        assertEquals(listOf("TwitchFollower()"), settings.global.conditions)
        assertTrue(settings.servers.isEmpty())
        assertEquals(listOf("d7c1db4d-e57b-488b-b8bc-4462fe49a3e8", "Scarsz"), settings.global.bypass)
        assertTrue(settings.global.deniedMessage.contains("\n"))
        assertFalse(File(dir, Settings.FILE_NAME).readText().contains("servers:"))
    }

    @Test
    fun `an existing config is not overwritten`(@TempDir dir: File) {
        val yaml = "api:\n  application-id: \"mine\"\n"
        load(dir, yaml)
        assertEquals(yaml, File(dir, Settings.FILE_NAME).readText())
    }

    @Test
    fun `proxy default config has the proxy options`(@TempDir dir: File) {
        val settings = Settings.load(dir, proxy = true)
        assertTrue(File(dir, Settings.FILE_NAME).readText().contains("servers:"))
        assertEquals(listOf("TwitchFollower()"), settings.global.conditions)
        assertTrue(settings.servers.isEmpty())
    }

    @Test
    fun `a partial config fills in the defaults`(@TempDir dir: File) {
        val settings = load(dir, "api:\n  application-id: \" app \"\n  application-secret: \" sec \"\ngatekeeper:\n  messages:\n    denied: \"&cgo away\"\n")

        assertEquals("app", settings.applicationId)
        assertEquals("sec", settings.applicationSecret)
        assertTrue(settings.hasApplication)
        assertNull(settings.serverToken)
        assertEquals(defaults.cacheSeconds, settings.cacheSeconds)
        assertEquals(defaults.bypassAdmins, settings.bypassAdmins)
        assertEquals(defaults.onApiError, settings.onApiError)
        assertEquals("&cgo away", settings.global.deniedMessage)
        assertEquals(defaults.global.errorMessage, settings.global.errorMessage)
        assertEquals(defaults.global.conditions, settings.global.conditions)
        assertEquals(defaults.global.bypass, settings.global.bypass)
    }

    @Test
    fun `a config without a gatekeeper section uses the default gatekeeper`(@TempDir dir: File) {
        val settings = load(dir, "api:\n  application-id: \"app\"\n  application-secret: \"sec\"\n")
        assertEquals(defaults.global, settings.global)
    }

    @Test
    fun `an empty condition list turns the gatekeeper off`(@TempDir dir: File) {
        val settings = load(dir, "gatekeeper:\n  conditions: []\n  bypass: []\n")
        assertTrue(settings.global.conditions.isEmpty())
        assertTrue(settings.global.bypass.isEmpty())
    }

    @Test
    fun `blank conditions and bypass entries are dropped`(@TempDir dir: File) {
        val settings = load(dir, "gatekeeper:\n  conditions: [\" TwitchFollower() \", \"\", \"  \"]\n  bypass: [\"\", \" Notch \"]\n")
        assertEquals(listOf("TwitchFollower()"), settings.global.conditions)
        assertEquals(listOf("Notch"), settings.global.bypass)
    }

    @Test
    fun `blank credentials mean none`(@TempDir dir: File) {
        val settings = load(dir, "api:\n  application-id: \"  \"\n  application-secret: \"\"\n  server-token: \"   \"\n")
        assertNull(settings.applicationId)
        assertNull(settings.applicationSecret)
        assertNull(settings.serverToken)
        assertFalse(settings.hasCredentials)
        assertEquals(Credentials.None, settings.credentials())
    }

    @Test
    fun `a legacy server token is read and used when there's no application`(@TempDir dir: File) {
        val settings = load(dir, "api:\n  server-token: \" tok \"\n")
        assertEquals("tok", settings.serverToken)
        assertTrue(settings.usesLegacyToken)
        assertTrue(settings.hasCredentials)
        @Suppress("DEPRECATION")
        assertTrue(settings.credentials() is Credentials.ServerToken)
    }

    @Test
    fun `an application wins over a legacy server token`(@TempDir dir: File) {
        val settings = load(dir, "api:\n  application-id: \"app\"\n  application-secret: \"sec\"\n  server-token: \"tok\"\n")
        assertFalse(settings.usesLegacyToken)
        assertEquals("Credentials.Application(app, ***)", settings.credentials().toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["-1", "-3600"])
    fun `a negative cache-seconds becomes 0`(value: String, @TempDir dir: File) {
        assertEquals(0, load(dir, "api:\n  cache-seconds: $value\n").cacheSeconds)
    }

    @Test
    fun `cache-seconds is read`(@TempDir dir: File) {
        assertEquals(300, load(dir, "api:\n  cache-seconds: 300\n").cacheSeconds)
    }

    @ParameterizedTest
    @ValueSource(strings = ["allow", "ALLOW", "' Allow '"])
    fun `on-api-error allow is read in any case`(value: String, @TempDir dir: File) {
        assertEquals(ApiErrorPolicy.ALLOW, load(dir, "gatekeeper:\n  on-api-error: $value\n").onApiError)
    }

    @ParameterizedTest
    @ValueSource(strings = ["deny", "maybe", "yes", "true", "''"])
    fun `an unknown on-api-error falls back to deny`(value: String, @TempDir dir: File) {
        assertEquals(ApiErrorPolicy.DENY, load(dir, "gatekeeper:\n  on-api-error: $value\n").onApiError)
    }

    @Test
    fun `a server's bypass list adds to the global list`(@TempDir dir: File) {
        val settings = load(dir, """
            gatekeeper:
              bypass: ["Notch"]
              servers:
                event:
                  bypass: ["jeb_"]
                lobby:
                  conditions: ["TwitchFollower()"]
        """.trimIndent(), proxy = true)

        assertEquals(listOf("Notch"), settings.global.bypass)
        assertEquals(listOf("Notch", "jeb_"), settings.servers.getValue("event").bypass)
        assertEquals(listOf("Notch"), settings.servers.getValue("lobby").bypass)
    }

    @Test
    fun `server-specific messages override the global ones`(@TempDir dir: File) {
        val settings = load(dir, """
            gatekeeper:
              messages:
                denied: "global denied"
                error: "global error"
              servers:
                event:
                  messages:
                    denied: "event denied"
                vip:
                  messages:
                    denied: "vip denied"
                    error: "vip error"
                lobby: {}
        """.trimIndent(), proxy = true)

        val servers = settings.servers
        assertEquals("event denied" to "global error", servers.getValue("event").let { it.deniedMessage to it.errorMessage })
        assertEquals("vip denied" to "vip error", servers.getValue("vip").let { it.deniedMessage to it.errorMessage })
        assertEquals("global denied" to "global error", servers.getValue("lobby").let { it.deniedMessage to it.errorMessage })
    }

    @Test
    fun `server realms keep their names and conditions, in order`(@TempDir dir: File) {
        val settings = load(dir, """
            gatekeeper:
              conditions: ["TwitchFollower()"]
              servers:
                zeta:
                  conditions: ["YouTubeMember()"]
                alpha: {}
        """.trimIndent(), proxy = true)

        assertEquals(listOf("zeta", "alpha"), settings.servers.keys.toList())
        assertEquals("zeta", settings.servers.getValue("zeta").name)
        assertEquals(listOf("YouTubeMember()"), settings.servers.getValue("zeta").conditions)
        // a server's conditions don't inherit: a server without any has no requirements of its own
        assertTrue(settings.servers.getValue("alpha").conditions.isEmpty())
        assertNull(settings.global.name)
    }

    @Test
    fun `servers are ignored outside a proxy`(@TempDir dir: File) {
        val settings = load(dir, "gatekeeper:\n  servers:\n    event:\n      conditions: [\"TwitchSubscriber(3)\"]\n")
        assertTrue(settings.servers.isEmpty())
    }
}
