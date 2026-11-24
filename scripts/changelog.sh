#!/usr/bin/env bash
# Generates a release's changelog from the commits: the subjects of the commits that changed a component since the
# previous release, as a Markdown list. The commits are the changelog; nothing is kept in the repository.
#
#   scripts/changelog.sh COMPONENT [REF [BASE]]   one component's changes, e.g. for Modrinth (minecraft) or npm
#   scripts/changelog.sh all [REF [BASE]]         every changed component under its own heading (GitHub release)
#
#   COMPONENT  lib_jvm, lib_go, lib_js, caddy or minecraft (what counts as a change: scripts/components.sh)
#   REF        the release (default HEAD)
#   BASE       the previous release (default: the latest vX.Y.Z tag before REF; none -> the whole history)
#
# Merge commits and the release's own version bumps are left out. Each entry links its commit, and the list ends
# with a link to the full comparison. Set REPO_URL to link somewhere other than the GitHub repository.
#
#   MODRINTH_CHANGELOG="$(bash scripts/changelog.sh minecraft vX.Y.Z)" ./gradlew modrinth
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
source scripts/components.sh

usage() { sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
[[ $# -ge 1 ]] || usage
what=$1
[[ $what == all ]] || is_component "$what" || usage

ref=${2:-HEAD}
base=${3:-$(previous_release "$ref")}
verify_commit "$ref"
[[ -z $base ]] || verify_commit "$base"
repo=${REPO_URL:-https://github.com/MinecraftAuthentication/integrations}

declare -A TITLE=([minecraft]="Minecraft (plugins, proxies and mods)" [lib_jvm]="lib/jvm (me.minecraftauth:lib)"
    [lib_go]="lib/go" [lib_js]="lib/js (@minecraftauth/lib)" [caddy]="Caddy module")

# entries COMPONENT -> "- subject ([abc1234](url))" per commit, newest first
entries() {
    local paths
    mapfile -t paths < <(component_paths "$1")
    # lib/js's manifests are excluded from its paths for change detection; here, dependency updates should show
    [[ $1 == lib_js ]] && paths=(lib/js "${NOT_SHIPPED[@]}")
    git log --no-merges --format='%s%x09%h%x09%H' ${base:+"$base..$ref"} ${base:-"$ref"} -- "${paths[@]}" |
        grep -v -E '^(Release [0-9]|Back to [0-9][^	]*-SNAPSHOT)' |
        while IFS=$'\t' read -r subject short full; do
            printf -- '- %s ([%s](%s/commit/%s))\n' "$subject" "$short" "$repo" "$full"
        done || true
}

footer() {
    local to
    to=$(git describe --tags --exact-match --match 'v[0-9]*' "$ref" 2>/dev/null || git rev-parse --short "$ref")
    if [[ -n $base ]]; then
        printf '\n**Full changes:** [%s...%s](%s/compare/%s...%s)\n' "$base" "$to" "$repo" "$base" "$to"
    fi
}

if [[ $what != all ]]; then
    list=$(entries "$what")
    if [[ -z $list ]]; then
        echo "No $what changes in $ref since ${base:-the beginning}" >&2
        exit 0
    fi
    printf '%s\n' "$list"
    footer
    exit 0
fi

any=false
for component in minecraft lib_jvm lib_go lib_js caddy; do
    list=$(entries "$component")
    [[ -z $list ]] && continue
    $any && echo
    printf '## %s\n\n%s\n' "${TITLE[$component]}" "$list"
    any=true
done
$any || echo "No changes in $ref since ${base:-the beginning}" >&2
$any && footer
exit 0
