package conventions

import org.gradle.api.provider.Property

abstract class McAuthExtension {
    /** Java toolchain + bytecode level for the module. */
    abstract val javaVersion: Property<Int>
}
