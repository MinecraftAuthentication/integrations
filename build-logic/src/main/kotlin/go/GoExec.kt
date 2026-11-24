package go

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
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
 * Runs `go <args>` in a Go module directory. Output goes to the console and to [log], which doubles as the
 * task's output so `go vet` / `go test` are up to date (and build-cacheable) when no Go source changed.
 */
@CacheableTask
abstract class GoExec @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    /** The `go` command, from the `mcauth.go.executable` property (default: `go` on the PATH). */
    @get:Input
    abstract val executable: Property<String>

    /** `go env GOVERSION GOOS GOARCH GOEXE GOFLAGS`: a new toolchain, target or flags invalidate cached results. */
    @get:Input
    abstract val toolchain: Property<String>

    @get:Input
    abstract val args: ListProperty<String>

    /** Extra environment, e.g. CGO_ENABLED=1 for -race. */
    @get:Input
    abstract val environment: MapProperty<String, String>

    /** Every file the Go toolchain reads: .go sources, go.mod/go.sum, go.work, testdata. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val workingDir: DirectoryProperty

    @get:OutputFile
    abstract val log: RegularFileProperty

    /** Files the command writes besides the log (binaries, coverage profiles). */
    @get:OutputFiles
    abstract val products: ConfigurableFileCollection

    @TaskAction
    fun run() {
        val logFile = log.get().asFile
        logFile.parentFile.mkdirs()
        val captured = ByteArrayOutputStream()
        val console = Tee(captured, System.out)
        val result = exec.exec {
            workingDir(this@GoExec.workingDir)
            commandLine(listOf(this@GoExec.executable.get()) + this@GoExec.args.get())
            environment(this@GoExec.environment.get())
            standardOutput = console
            errorOutput = console
            isIgnoreExitValue = true
        }
        logFile.writeBytes(captured.toByteArray())
        if (result.exitValue != 0) {
            throw GradleException("`go ${args.get().joinToString(" ")}` failed with exit code ${result.exitValue} (log: $logFile)")
        }
    }

    private class Tee(private val a: OutputStream, private val b: OutputStream) : OutputStream() {
        override fun write(byte: Int) { a.write(byte); b.write(byte) }
        override fun write(bytes: ByteArray, off: Int, len: Int) { a.write(bytes, off, len); b.write(bytes, off, len) }
        override fun flush() { a.flush(); b.flush() }
    }
}
