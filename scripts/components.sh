# Sourced by changed-components.sh and changelog.sh: what belongs to each component, as git pathspecs from the
# repository root, and the helpers both scripts need. Not meant to be run on its own.
#
# A component's paths are what ends up in its artifact. Its shipped dependencies count too (lib/jvm is shaded into
# every Minecraft jar), and so do the shared build inputs that shape it. Tests and docs (*.md) don't count.

COMPONENTS=(lib_jvm lib_go lib_js caddy minecraft)

# appended to every component's pathspecs
NOT_SHIPPED=(':(exclude,glob)**/*_test.go' ':(exclude,glob)**/src/test/**' ':(exclude,glob)**/src/testFixtures/**'
    ':(exclude,glob)lib/js/test/**' ':(exclude,glob)**/*.md')

JVM_BUILD=(gradle/libs.versions.toml build-logic/build.gradle.kts build-logic/settings.gradle.kts
    build-logic/src/main/kotlin/conventions/McAuthExtension.kt
    'build-logic/src/main/kotlin/conventions/mcauth.kotlin-*.gradle.kts')

PATHS_lib_jvm=(lib/jvm "${JVM_BUILD[@]}")
PATHS_minecraft=(minecraft "${PATHS_lib_jvm[@]}" build-logic/src/main/kotlin/modrinth
    build-logic/src/main/kotlin/conventions/mcauth.shadow.gradle.kts
    build-logic/src/main/kotlin/conventions/mcauth.shaded-plugin.gradle.kts
    build-logic/src/main/kotlin/conventions/mcauth.modrinth.gradle.kts)
PATHS_lib_go=(lib/go)
PATHS_caddy=(caddy)
# lib/js/package.json and package-lock.json count too, but every release bumps the version in them: see
# js_manifests_changed in changed-components.sh
PATHS_lib_js=(lib/js ':(exclude)lib/js/package.json' ':(exclude)lib/js/package-lock.json')

# component_paths NAME -> the component's pathspecs, one per line
component_paths() {
    local -n paths="PATHS_$1"
    printf '%s\n' "${paths[@]}" "${NOT_SHIPPED[@]}"
}

is_component() {
    local c
    for c in "${COMPONENTS[@]}"; do [[ $c == "$1" ]] && return 0; done
    return 1
}

# previous_release REF -> the latest vX.Y.Z tag before REF (its parent, so a tagged REF gives the release before
# it), or nothing for the first release
previous_release() {
    git describe --tags --abbrev=0 --match 'v[0-9]*' "$1^" 2>/dev/null || true
}

verify_commit() {
    git rev-parse --quiet --verify "$1^{commit}" >/dev/null || { echo "Not a commit: $1" >&2; exit 2; }
}
