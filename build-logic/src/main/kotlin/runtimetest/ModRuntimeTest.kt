package runtimetest

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.jvm.toolchain.JavaLauncher
import java.io.File
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Runtime test of a built mod jar on a real, production (non-dev) dedicated server:
 *
 *  1. installs the loader's server with its official installer (cached in [serverDir] until the versions change)
 *  2. puts the shipped mod jar (and [extraMods], e.g. Fabric API) in `mods/` and starts the server
 *  3. checks the mod loaded: default config written, startup log, `/minecraftauth` registered and answering
 *  4. writes a test config, `/mcauth reload`s it, then logs in with an offline-mode client ([LoginProbe]):
 *     a player on the bypass list must get in, any other player must be kicked by the gatekeeper through
 *     the `PlayerList.canPlayerLogin` mixin. MinecraftAuth.me is made unreachable (HTTPS goes to a dead proxy),
 *     so that kick is the `on-api-error: deny` message and no real API call is made.
 *  5. stops the server with `stop` and expects it to exit.
 *
 * The server console is in `<serverDir>/console.log` (and on the Gradle console with `--info`).
 */
@UntrackedTask(because = "Starts a real Minecraft server every time")
abstract class ModRuntimeTest : DefaultTask() {
    /** `fabric`, `forge` or `neoforge`. */
    @get:Input abstract val loader: Property<String>
    @get:Input abstract val minecraftVersion: Property<String>
    /** Fabric only: the Fabric Loader version to install. */
    @get:Input @get:Optional abstract val fabricLoaderVersion: Property<String>
    /** The name the mod passes to `MinecraftAuthService` (`Fabric`, `Forge`, `NeoForge`). */
    @get:Input abstract val platformName: Property<String>
    /** The version `/minecraftauth` should report. */
    @get:Input abstract val expectedVersion: Property<String>

    /** The jar being tested, exactly as shipped. */
    @get:InputFile @get:PathSensitive(PathSensitivity.NAME_ONLY) abstract val modJar: RegularFileProperty
    /** Other mods the server needs (Fabric API). */
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY) abstract val extraMods: ConfigurableFileCollection
    /** The loader's installer jar (Fabric installer, Forge / NeoForge installer). */
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY) abstract val installer: ConfigurableFileCollection

    /** The Java the server runs on (and the installer). */
    @get:Nested abstract val javaLauncher: Property<JavaLauncher>
    @get:Input abstract val jvmArgs: ListProperty<String>
    /** How long the server may take to start (first starts of Forge are slow). */
    @get:Input abstract val startupTimeoutSeconds: Property<Long>

    @get:Internal abstract val serverDir: DirectoryProperty

    init {
        group = "verification"
        jvmArgs.convention(listOf("-Xmx2G"))
        startupTimeoutSeconds.convention(900L)
    }

    private val passed = mutableListOf<String>()

    @TaskAction
    fun run() {
        val dir = serverDir.get().asFile
        val java = javaLauncher.get().executablePath.asFile.absolutePath
        val launch = install(dir, java)
        prepare(dir)

        val port = ServerSocket(0).use { it.localPort }
        val deadPort = ServerSocket(0).use { it.localPort } // nothing listens here: MinecraftAuth.me is "unreachable"
        writeServerProperties(dir, port)

        val command = listOf(java) + jvmArgs.get() + listOf(
            "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
            "-Dterminal.jline=false", "-Dterminal.ansi=false",
            "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=$deadPort",
            "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=$deadPort",
        ) + launch + listOf("nogui")
        logger.lifecycle("Starting ${loader.get()} ${minecraftVersion.get()} server in $dir on port $port")
        logger.info("Command: ${command.joinToString(" ")}")

        val console = ServerConsole(ProcessBuilder(command).directory(dir).redirectErrorStream(true).start(), File(dir, "console.log"))
        try {
            test(dir, console, port)
        } catch (e: Throwable) {
            console.destroy()
            throw GradleException(
                "Runtime test of ${modJar.get().asFile.name} failed: ${e.message}\n" +
                    (if (passed.isEmpty()) "" else "Passed before that:\n" + passed.joinToString("\n") { "  [ok] $it" } + "\n") +
                    "Last console lines:\n" + console.tail(40).joinToString("\n") { "  | $it" } +
                    "\nFull console: ${console.file}", e)
        }
        logger.lifecycle("Runtime test passed for ${modJar.get().asFile.name} (${passed.size} checks)")
    }

    private fun test(dir: File, console: ServerConsole, port: Int) {
        val platform = platformName.get()
        val version = expectedVersion.get()

        console.await("server to finish starting", Regex("""Done \([\d.,]+s\)!"""), startupTimeoutSeconds.get())
        pass("server started")

        val config = File(dir, "config/minecraftauth/MinecraftAuth.yml")
        check(config.isFile && config.readText().contains("application-id")) { "default config was not written to $config" }
        pass("default config written to config/minecraftauth/MinecraftAuth.yml")
        console.await("startup warning about the missing credentials", Regex("""No application credentials are set in MinecraftAuth\.yml"""), 0)
        pass("mod initialised (gatekeeper warned about the missing credentials)")

        console.send("minecraftauth")
        console.await("/minecraftauth version line", Regex("""MinecraftAuth v${Regex.escape(version)} \(${Regex.escape(platform)}\)"""), 60)
        console.await("/minecraftauth status line", Regex("""No application credentials configured; the gatekeeper is off\."""), 60)
        pass("/minecraftauth registered and reports v$version ($platform)")

        config.writeText(TEST_CONFIG)
        console.send("mcauth reload")
        console.await("reload confirmation", Regex("""MinecraftAuth configuration reloaded\."""), 60)
        console.await("gatekeeper enabled after reload", Regex("""Gatekeeper is checking 1 condition\b"""), 0)
        pass("/mcauth alias registered, reload picked up the test config")

        val probe = LoginProbe("127.0.0.1", port)
        val protocol = probe.protocol()
        pass("status ping answered (protocol $protocol)")

        val denied = probe.login(DENIED_PLAYER, protocol)
        check(denied is LoginProbe.Outcome.Kicked && denied.reason.contains(ERROR_MARKER)) {
            "$DENIED_PLAYER should have been kicked with the on-api-error message, got $denied"
        }
        console.await("gatekeeper denial log line", Regex("""Denying $DENIED_PLAYER: their accounts couldn't be checked"""), 30)
        pass("$DENIED_PLAYER kicked by the canPlayerLogin mixin with the on-api-error message")

        val bypassed = probe.login(BYPASS_PLAYER, protocol)
        check(bypassed is LoginProbe.Outcome.Accepted) { "$BYPASS_PLAYER is on the bypass list and should have got in, got $bypassed" }
        console.await("bypass log line", Regex("""$BYPASS_PLAYER is on the bypass list, letting them in"""), 30)
        pass("$BYPASS_PLAYER (bypass list) let in")

        val problems = console.lines().filter { line -> FORBIDDEN.any { it.containsMatchIn(line) } }
        check(problems.isEmpty()) { "errors from the mod in the console:\n" + problems.joinToString("\n") }
        pass("no mixin or MinecraftAuth errors in the console")

        console.send("stop")
        val exit = console.awaitExit(180) ?: error("server did not exit within 180s of `stop`")
        check(exit == 0) { "server exited with code $exit after `stop`" }
        pass("server stopped cleanly")
    }

    private fun pass(what: String) {
        passed += what
        logger.lifecycle("  [ok] $what")
    }

    /** Installs the server if [dir] doesn't hold this exact loader version yet; returns the launch arguments. */
    private fun install(dir: File, java: String): List<String> {
        val installerJar = installer.singleFile
        val key = listOf(loader.get(), minecraftVersion.get(), fabricLoaderVersion.orNull, installerJar.name).joinToString("|")
        val marker = File(dir, ".installed")
        if (!marker.isFile || marker.readText() != key) {
            logger.lifecycle("Installing ${loader.get()} server for Minecraft ${minecraftVersion.get()} into $dir")
            dir.deleteRecursively()
            dir.mkdirs()
            val args = when (loader.get()) {
                "fabric" -> listOf("server", "-dir", dir.absolutePath, "-mcversion", minecraftVersion.get(),
                    "-loader", fabricLoaderVersion.get(), "-downloadMinecraft")
                else -> listOf("--installServer", dir.absolutePath)
            }
            val log = File(dir, "installer.log")
            val process = ProcessBuilder(listOf(java, "-jar", installerJar.absolutePath) + args)
                .directory(dir).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(20, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                throw GradleException("Installer timed out (log: $log)")
            }
            if (process.exitValue() != 0) throw GradleException("Installer failed with exit code ${process.exitValue()} (log: $log)")
            marker.writeText(key)
        }
        return launchArgs(dir)
    }

    private fun launchArgs(dir: File): List<String> {
        if (loader.get() == "fabric") return listOf("-jar", "fabric-server-launch.jar")
        // Forge 1.17+ / NeoForge: libraries/<group>/<loader>/<version>/{win,unix}_args.txt
        val argsName = if (System.getProperty("os.name").startsWith("Windows")) "win_args.txt" else "unix_args.txt"
        File(dir, "libraries").walkTopDown().firstOrNull { it.name == argsName }?.let {
            return listOf("@" + it.relativeTo(dir).invariantSeparatorsPath)
        }
        // Forge 1.16.5: a runnable forge-<mc>-<forge>.jar
        dir.listFiles { f -> f.isFile && f.extension == "jar" && f.name.matches(Regex("(neo)?forge-.*")) && !f.name.contains("installer") }
            ?.firstOrNull()?.let { return listOf("-jar", it.name) }
        throw GradleException("Don't know how to launch the server installed in $dir")
    }

    /** Fresh world, mods and MinecraftAuth config every run; the installed server is kept. */
    private fun prepare(dir: File) {
        listOf("mods", "world", "logs", "config/minecraftauth", "crash-reports", "console.log").forEach { File(dir, it).deleteRecursively() }
        val mods = File(dir, "mods").apply { mkdirs() }
        (listOf(modJar.get().asFile) + extraMods.files).forEach { it.copyTo(File(mods, it.name), overwrite = true) }
        File(dir, "eula.txt").writeText("eula=true\n")
    }

    private fun writeServerProperties(dir: File, port: Int) {
        val legacyLevelType = minecraftVersion.get().let { it.startsWith("1.16") || it.startsWith("1.17") || it.startsWith("1.18") }
        File(dir, "server.properties").writeText(
            """
            server-ip=127.0.0.1
            server-port=$port
            online-mode=false
            enforce-secure-profile=false
            white-list=false
            network-compression-threshold=-1
            level-type=${if (legacyLevelType) "flat" else "minecraft\\:flat"}
            generate-structures=false
            spawn-protection=0
            enable-query=false
            enable-rcon=false
            sync-chunk-writes=false
            motd=MinecraftAuth runtime test
            """.trimIndent() + "\n"
        )
    }

    /** The server process, its console captured line by line. */
    private inner class ServerConsole(private val process: Process, val file: File) {
        private val lines = ArrayList<String>()
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private val writer = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)

        init {
            Thread({
                file.bufferedWriter(StandardCharsets.UTF_8).use { out ->
                    process.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { raw ->
                        val line = clean(raw)
                        out.write(line); out.newLine(); out.flush()
                        logger.info("[server] $line")
                        lock.withLock { lines += line; changed.signalAll() }
                    }
                }
                lock.withLock { changed.signalAll() }
            }, "runtime-test-console").apply { isDaemon = true; start() }
        }

        fun lines(): List<String> = lock.withLock { ArrayList(lines) }
        fun tail(n: Int): List<String> = lines().takeLast(n)

        fun send(command: String) {
            logger.info("[console] > $command")
            writer.write(command); writer.newLine(); writer.flush()
        }

        /** Waits up to [seconds] for a console line matching [pattern] (anywhere in the output so far). */
        fun await(what: String, pattern: Regex, seconds: Long) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            lock.withLock {
                var checked = 0
                while (true) {
                    while (checked < lines.size) if (pattern.containsMatchIn(lines[checked++])) return
                    if (!process.isAlive) throw IllegalStateException("server exited (code ${process.exitValue()}) while waiting for $what")
                    val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                    if (left <= 0) throw IllegalStateException("timed out after ${seconds}s waiting for $what (/$pattern/)")
                    changed.await(minOf(left, 1000L), TimeUnit.MILLISECONDS)
                }
            }
        }

        fun awaitExit(seconds: Long): Int? = if (process.waitFor(seconds, TimeUnit.SECONDS)) process.exitValue() else null

        fun destroy() {
            if (!process.isAlive) return
            runCatching { send("stop") }
            if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(30, TimeUnit.SECONDS)
        }

        /** Strips ANSI escapes and `§` color codes so assertions match plain text. */
        private fun clean(line: String) = line.replace(ANSI_ESCAPE, "").replace(COLOR_CODE, "")
    }

    companion object {
        const val DENIED_PLAYER = "McAuthDenied"
        const val BYPASS_PLAYER = "McAuthBypass"
        const val ERROR_MARKER = "RUNTIME-TEST-API-ERROR"

        val TEST_CONFIG = """
            api:
              application-id: "RuntimeTestApp01"
              application-secret: "runtime-test-secret"
              cache-seconds: 60
            gatekeeper:
              bypass:
                - "$BYPASS_PLAYER"
              bypass-admins: true
              on-api-error: deny
              messages:
                denied: "RUNTIME-TEST-DENIED"
                error: "$ERROR_MARKER"
              conditions:
                - Linked()
        """.trimIndent() + "\n"

        private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;]*[A-Za-z]")
        private val COLOR_CODE = Regex("§.")

        private val FORBIDDEN = listOf(
            Regex("""\bat me\.minecraftauth\."""),
            Regex("""MinecraftAuth check failed"""),
            Regex("""Mixin apply.*failed""", RegexOption.IGNORE_CASE),
            Regex("""InvalidInjectionException|InjectionError"""),
            Regex("""Error evaluating '"""),
            Regex("""Failed to reload MinecraftAuth\.yml"""),
        )
    }
}
