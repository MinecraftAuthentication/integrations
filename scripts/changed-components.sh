#!/usr/bin/env bash
# Which components need publishing in a release: those with changes since the previous release.
#
# The whole repository shares one version and one release tag (vX.Y.Z), but a component is only published when
# something that ends up in its artifact changed since the previous vX.Y.Z tag (scripts/components.sh). Tests,
# docs (*.md) and the version number itself don't count.
#
#   scripts/changed-components.sh [REF [BASE]]
#
#   REF   the release commit (default HEAD)
#   BASE  what to compare it with (default: the latest vX.Y.Z tag before REF; none -> everything changed)
#
# Prints one `component=true|false` line per component (lib_jvm, lib_go, lib_js, caddy, minecraft), ready to append
# to $GITHUB_OUTPUT, and a summary on stderr.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
source scripts/components.sh

ref=${1:-HEAD}
base=${2:-$(previous_release "$ref")}
verify_commit "$ref"
[[ -z $base ]] || verify_commit "$base"

changed() { # component
    [[ -z $base ]] && return 0
    local paths
    mapfile -t paths < <(component_paths "$1")
    ! git diff --quiet "$base" "$ref" -- "${paths[@]}"
}

# package.json and its lockfile with every "version" line dropped: a dependency change still shows up (its
# resolved/integrity lines change too), the release's version bump doesn't
js_manifests_changed() {
    [[ -z $base ]] && return 0
    local file
    for file in lib/js/package.json lib/js/package-lock.json; do
        if ! diff -q <(git show "$base:$file" 2>/dev/null | grep -v '^ *"version": ') \
                     <(git show "$ref:$file" 2>/dev/null | grep -v '^ *"version": ') >/dev/null; then
            return 0
        fi
    done
    return 1
}

echo "Changes in $ref since ${base:-the beginning (no earlier vX.Y.Z tag)}:" >&2
for component in "${COMPONENTS[@]}"; do
    result=false
    if changed "$component" || { [[ $component == lib_js ]] && js_manifests_changed; }; then
        result=true
    fi
    echo "$component=$result"
    printf '  %-10s %s\n' "$component" "$([[ $result == true ]] && echo "changed -> publish" || echo "unchanged")" >&2
done
