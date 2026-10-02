#!/bin/sh
#
# release-preflight.sh
#
# The gate a release must pass before its tag is pushed. Run it on the exact
# commit you intend to tag, with the version you intend to release:
#
#   scripts/release-preflight.sh 0.15.0
#
# Exit 0 means "this tree is safe to tag". Any other exit means do not tag.
#
# Why this exists: pushing a `v*` tag publishes eight modules to Maven Central,
# which is permanent and cannot be undone. Regular PR CI does not cover
# everything that publish happens to compile (see the module drift check below),
# and it never runs the common-metadata compile, so a commit can be green on
# main and still fail eight minutes into the publish workflow — after the tag
# exists. This script closes the gap that keeps reopening.
#
# Deliberately ONE `./gradlew` invocation. On a memory-constrained machine the
# build admission hook allows a single build at a time; splitting these tasks
# across calls gets the later ones refused. Add tasks to the list, not calls.

set -eu

REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || true)
if [ -z "$REPO_ROOT" ]; then
    printf 'release-preflight: not inside a git repository.\n' >&2
    exit 1
fi
cd "$REPO_ROOT"

EXPECTED_VERSION=${1:-}
if [ -z "$EXPECTED_VERSION" ]; then
    printf 'release-preflight: usage: scripts/release-preflight.sh <version>   (e.g. 0.15.0)\n' >&2
    exit 1
fi

VERSION_FILE="ampere-core/src/commonMain/kotlin/link/socket/ampere/AmpereVersion.kt"

# --- 1. The two version facts must agree with each other and with the argument.
#
# `verifyAmpereVersionConstant` (wired into :ampere-core:jvmTest) also checks the
# first half, but checking it here fails in a second instead of ten minutes, and
# only here can we check it against the version you actually mean to release.
GRADLE_VERSION=$(grep '^ampereVersion=' gradle.properties | cut -d'=' -f2)
CONSTANT_VERSION=$(sed -n 's/^const val AMPERE_RUNTIME_VERSION: String = "\(.*\)"$/\1/p' "$VERSION_FILE")

if [ "$GRADLE_VERSION" != "$EXPECTED_VERSION" ]; then
    printf 'release-preflight: you asked to release %s but gradle.properties declares ampereVersion=%s.\n' \
        "$EXPECTED_VERSION" "$GRADLE_VERSION" >&2
    printf '  main carries the NEXT unreleased version, so tag the commit BEFORE the bump, not after it.\n' >&2
    printf '  See docs/RELEASING.md, "How the version moves".\n' >&2
    exit 1
fi

if [ "$CONSTANT_VERSION" != "$GRADLE_VERSION" ]; then
    printf 'release-preflight: AMPERE_RUNTIME_VERSION is "%s" but gradle.properties declares %s.\n' \
        "$CONSTANT_VERSION" "$GRADLE_VERSION" >&2
    printf '  Both move together. Use scripts/bump-version.sh, which edits both.\n' >&2
    exit 1
fi

# --- 2. The tree must be clean, or you are not validating what you will tag.
if [ -n "$(git status --porcelain)" ]; then
    printf 'release-preflight: working tree is dirty. Commit or discard first — a tag points at a\n' >&2
    printf '  commit, so anything uncommitted is validated here and then not published.\n' >&2
    exit 1
fi

# --- 3. The tag must not already exist. A published tag is never force-moved.
if git rev-parse -q --verify "refs/tags/v$EXPECTED_VERSION" >/dev/null; then
    printf 'release-preflight: tag v%s already exists. Releases are immutable; pick the next version.\n' \
        "$EXPECTED_VERSION" >&2
    exit 1
fi

# --- 4. Modules that publish but are not assembled by PR CI.
#
# ci.yml assembles ampere-core, ampere-cli, ampere-compose, ampere-eval and
# ampere-work-linear. The rest of the publish list is compiled by nobody until
# the tag lands, and a red one of them fails the publish job partway through —
# after earlier modules have already reached Central. Compile them here.
PREFLIGHT_MODULES=":ampere-phosphor :ampere-core-test-fixtures :ampere-bindings-apple :ampere-bindings-android"

# Keep that list honest: the next module to opt into publishing must be covered
# by either this script or ci.yml, or it falls into exactly the hole above. This
# is the same drift AMPR-271 found in publish.yml, one step earlier.
for build_file in */build.gradle.kts; do
    [ -f "$build_file" ] || continue
    grep -q 'mavenPublishing {' "$build_file" || continue

    module=":${build_file%/build.gradle.kts}"
    case " $PREFLIGHT_MODULES " in
        *" $module "*) continue ;;
    esac
    grep -q "$module:assemble" .github/workflows/ci.yml && continue

    printf 'release-preflight: %s publishes to Maven Central but is compiled by neither this\n' "$module" >&2
    printf '  script nor .github/workflows/ci.yml. Add it to PREFLIGHT_MODULES here.\n' >&2
    exit 1
done

# --- 5. One build.
#
# ktlintCheck + verifyPublishWorkflowCoverage: the publish job runs these first
#   and fails the release on them, so find out now. Seconds, not a compile.
# :ampere-core:jvmTest: the primary gate. Also drags in
#   verifyAmpereVersionConstant and verifySqlDelightMigration.
# :ampere-core:compileCommonMainKotlinMetadata: the task no PR CI job runs. A
#   JVM-only stdlib call in commonMain compiles on the JVM target and fails
#   here — this is what broke the v0.5.0 publish after its PR had gone green.
#
# NOT here: :ampere-cli:jvmTest. It opens the real ~/.ampere/ampere.db and fails
# on a developer machine for reasons that have nothing to do with the release.
# CI runs it on every PR.
TASKS="ktlintCheck verifyPublishWorkflowCoverage :ampere-core:jvmTest :ampere-core:compileCommonMainKotlinMetadata"
for module in $PREFLIGHT_MODULES; do
    TASKS="$TASKS $module:compileKotlinJvm"
done

printf 'release-preflight: validating %s at %s\n' "$EXPECTED_VERSION" "$(git rev-parse --short HEAD)"
printf 'release-preflight: ./gradlew %s\n\n' "$TASKS"

# shellcheck disable=SC2086
./gradlew $TASKS

COMMIT=$(git rev-parse --short HEAD)
printf '\nrelease-preflight: PASS — v%s can be cut from %s.\n\n' "$EXPECTED_VERSION" "$COMMIT"
printf 'Next, and only if the line above says PASS:\n'
printf '  git tag v%s %s\n' "$EXPECTED_VERSION" "$COMMIT"
printf '  git push origin v%s\n' "$EXPECTED_VERSION"
