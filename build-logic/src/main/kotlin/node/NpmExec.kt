package node

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectories
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.inject.Inject

/**
 * Runs `npm <args>` in a package directory. Output goes to the console and to [log], which doubles as the
 * task's output so typecheck/test runs are up to date (and build-cacheable) when no source changed.
 */
@CacheableTask
abstract class NpmExec @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    /** The `npm` command, from the `mcauth.npm.executable` property (default: `npm` / `npm.cmd` on the PATH). */
    @get:Input
    abstract val executable: Property<String>

    /** `node --version`: a new Node.js invalidates cached results. */
    @get:Input
    abstract val toolchain: Property<String>

    @get:Input
    abstract val args: ListProperty<String>

    /** Every file the command reads: sources, tests, package.json, the lockfile, tool configs. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val workingDir: DirectoryProperty

    @get:OutputFile
    abstract val log: RegularFileProperty

    /** Files the command writes besides the log. */
    @get:OutputFiles
    abstract val products: ConfigurableFileCollection

    /** Directories the command writes (e.g. dist/). */
    @get:OutputDirectories
    abstract val productDirs: ConfigurableFileCollection

    @TaskAction
    fun run() {
        val logFile = log.get().asFile
        logFile.parentFile.mkdirs()
        val captured = ByteArrayOutputStream()
        val console = Tee(captured, System.out)
        val result = exec.exec {
            workingDir(this@NpmExec.workingDir)
            commandLine(listOf(this@NpmExec.executable.get()) + this@NpmExec.args.get())
            environment("CI", "true") // no interactive prompts, no watch mode
            standardOutput = console
            errorOutput = console
            isIgnoreExitValue = true
        }
        logFile.writeBytes(captured.toByteArray())
        if (result.exitValue != 0) {
            throw GradleException("`npm ${args.get().joinToString(" ")}` failed with exit code ${result.exitValue} (log: $logFile)")
        }
    }

    private class Tee(private val a: OutputStream, private val b: OutputStream) : OutputStream() {
        override fun write(byte: Int) { a.write(byte); b.write(byte) }
        override fun write(bytes: ByteArray, off: Int, len: Int) { a.write(bytes, off, len); b.write(bytes, off, len) }
        override fun flush() { a.flush(); b.flush() }
    }
}
