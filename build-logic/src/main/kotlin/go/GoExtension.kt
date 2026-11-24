package go

import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property

abstract class GoExtension {
    /** Binaries built on `assemble` into build/go/bin, e.g. `binaries.register("caddy") { pkg = "./cmd/caddy" }`. */
    abstract val binaries: NamedDomainObjectContainer<GoBinary>

    /** Run tests with -race (needs cgo, i.e. a C compiler). Defaults to the `mcauth.go.race` property. */
    abstract val race: Property<Boolean>

    /**
     * Sources outside this module that it builds against (other go.work modules, `replace` targets), so
     * changes there re-run this module's tasks.
     */
    abstract val extraSources: ConfigurableFileCollection
}

abstract class GoBinary(private val name: String) : Named {
    override fun getName() = name

    /** The main package to build, relative to the module, e.g. "./cmd/caddy". */
    abstract val pkg: Property<String>
}
