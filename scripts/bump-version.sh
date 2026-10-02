#!/bin/sh
#
# bump-version.sh
#
# Moves the Ampere version. There are two files, and they must move together:
#
#   gradle.properties                 ampereVersion=<version>
#   ampere-core/.../AmpereVersion.kt  AMPERE_RUNTIME_VERSION = "<version>"
#
#   scripts/bump-version.sh 0.16.0
#
# `verifyAmpereVersionConstant` (AMPR-365) fails the build when the two drift,
# so editing only gradle.properties — the shape of every bump up to 0.15.0 —
# is now a red build. This script is the reason you do not have to remember that.
#
# Run it on a branch, not on main. See docs/RELEASING.md step 5.

set -eu

REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || true)
if [ -z "$REPO_ROOT" ]; then
    printf 'bump-version: not inside a git repository.\n' >&2
    exit 1
fi
cd "$REPO_ROOT"

NEW_VERSION=${1:-}
if [ -z "$NEW_VERSION" ]; then
    printf 'bump-version: usage: scripts/bump-version.sh <version>   (e.g. 0.16.0)\n' >&2
    exit 1
fi

# Three numeric components, no suffix. Not a style preference: publish.yml
# compares the tag to this string exactly, and a `-SNAPSHOT` suffix on main
# would make the next tag's validation step fail. docs/RELEASING.md used to
# recommend exactly that; it no longer does.
if ! printf '%s' "$NEW_VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    printf 'bump-version: "%s" is not of the form MAJOR.MINOR.PATCH with no suffix.\n' "$NEW_VERSION" >&2
    exit 1
fi

VERSION_FILE="ampere-core/src/commonMain/kotlin/link/socket/ampere/AmpereVersion.kt"
OLD_VERSION=$(grep '^ampereVersion=' gradle.properties | cut -d'=' -f2)

if [ "$NEW_VERSION" = "$OLD_VERSION" ]; then
    printf 'bump-version: already at %s; nothing to do.\n' "$NEW_VERSION" >&2
    exit 1
fi

if git rev-parse -q --verify "refs/tags/v$NEW_VERSION" >/dev/null; then
    printf 'bump-version: v%s is already released. Bump past it, not onto it.\n' "$NEW_VERSION" >&2
    exit 1
fi

# In-place editing without `sed -i`, which takes an argument on BSD/macOS and
# not on GNU. Write beside the file, then move over it.
rewrite() {
    file=$1
    expression=$2
    sed "$expression" "$file" > "$file.bump-tmp"
    mv "$file.bump-tmp" "$file"
}

rewrite gradle.properties "s/^ampereVersion=.*$/ampereVersion=$NEW_VERSION/"
rewrite "$VERSION_FILE" \
    "s/^const val AMPERE_RUNTIME_VERSION: String = \".*\"$/const val AMPERE_RUNTIME_VERSION: String = \"$NEW_VERSION\"/"

# Both substitutions are anchored, so a file whose shape moved would silently
# not match. Read the result back rather than trusting the edit.
GRADLE_NOW=$(grep '^ampereVersion=' gradle.properties | cut -d'=' -f2)
CONSTANT_NOW=$(sed -n 's/^const val AMPERE_RUNTIME_VERSION: String = "\(.*\)"$/\1/p' "$VERSION_FILE")

if [ "$GRADLE_NOW" != "$NEW_VERSION" ] || [ "$CONSTANT_NOW" != "$NEW_VERSION" ]; then
    printf 'bump-version: edit did not take (gradle.properties=%s, constant=%s).\n' \
        "$GRADLE_NOW" "$CONSTANT_NOW" >&2
    printf '  One of the two declarations has moved or changed shape. Fix both by hand, then\n' >&2
    printf '  update this script and the task in ampere-core/build.gradle.kts that checks them.\n' >&2
    exit 1
fi

printf 'bump-version: %s -> %s\n\n' "$OLD_VERSION" "$NEW_VERSION"
git --no-pager diff -- gradle.properties "$VERSION_FILE"

printf '\nNext:\n'
printf '  git commit -am "Bump ampereVersion to %s"\n' "$NEW_VERSION"
printf '  gh pr create --base main --title "Bump ampereVersion to %s"\n' "$NEW_VERSION"
