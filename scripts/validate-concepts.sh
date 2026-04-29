#!/bin/sh
#
# validate-concepts.sh
#
# Frontmatter-driven staleness validator for docs/concepts/.
#
# For every concept file under docs/concepts/ (excluding _index.md and _template.md):
#   - parse YAML frontmatter (concept, tracked_sources)
#   - compare against the set of files changed between --base and --head
#   - if any tracked-source pattern matches a changed file, AND the concept file
#     itself was not changed, AND no commit between base..head carries a
#     `Concept-Verified: <ConceptName>` trailer, emit a warning.
#
# This script informs; it never blocks. Exit code is always 0.
#
# Usage:
#   scripts/validate-concepts.sh [--base <ref>] [--head <ref>] [--concepts-dir <dir>]
#
# Defaults: --base origin/main  --head HEAD  --concepts-dir docs/concepts
#
# Portability: POSIX shell. No bash 4-only features. Uses `awk`, `grep`, `git`,
# and `case` glob matching only.

set -u

BASE_REF="origin/main"
HEAD_REF="HEAD"
CONCEPTS_DIR="docs/concepts"

while [ $# -gt 0 ]; do
    case "$1" in
        --base)
            shift
            [ $# -gt 0 ] || { printf 'validate-concepts: --base requires a value\n' >&2; exit 0; }
            BASE_REF="$1"
            ;;
        --head)
            shift
            [ $# -gt 0 ] || { printf 'validate-concepts: --head requires a value\n' >&2; exit 0; }
            HEAD_REF="$1"
            ;;
        --concepts-dir)
            shift
            [ $# -gt 0 ] || { printf 'validate-concepts: --concepts-dir requires a value\n' >&2; exit 0; }
            CONCEPTS_DIR="$1"
            ;;
        -h|--help)
            sed -n '2,22p' "$0"
            exit 0
            ;;
        *)
            printf 'validate-concepts: unknown option: %s\n' "$1" >&2
            exit 0
            ;;
    esac
    shift
done

if [ ! -d "$CONCEPTS_DIR" ]; then
    # Nothing to validate; do not block.
    exit 0
fi

# Resolve diff: list of files changed between BASE_REF and HEAD_REF.
# If git rev-parse fails on either ref (e.g., a shallow clone with missing
# fetch-depth), fall back to an empty diff so we don't spam false positives.
if ! git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
    printf 'validate-concepts: base ref "%s" not found; skipping validation.\n' "$BASE_REF" >&2
    exit 0
fi
if ! git rev-parse --verify "$HEAD_REF" >/dev/null 2>&1; then
    printf 'validate-concepts: head ref "%s" not found; skipping validation.\n' "$HEAD_REF" >&2
    exit 0
fi

CHANGED_FILES=$(git diff --name-only "$BASE_REF" "$HEAD_REF" 2>/dev/null || true)
if [ -z "$CHANGED_FILES" ]; then
    exit 0
fi

# Collect verified concept names from `Concept-Verified:` commit trailers
# between BASE_REF and HEAD_REF. One per line.
VERIFIED_CONCEPTS=$(
    git log --format=%B "$BASE_REF..$HEAD_REF" 2>/dev/null \
        | awk '
            BEGIN { IGNORECASE = 1 }
            /^[Cc]oncept-[Vv]erified:[[:space:]]*/ {
                sub(/^[Cc]oncept-[Vv]erified:[[:space:]]*/, "")
                gsub(/[[:space:]]+$/, "")
                if (length($0) > 0) print $0
            }
        '
)

# Glob-match a single path against a single pattern using shell `case`.
#
# Patterns may contain `*` and `**`. POSIX `case` already supports `*`;
# for `**` we expand it to `*` (which makes `**` equivalent to `*`-across-
# slashes for our purposes — case glob in dash/ash treats `*` as
# "any string", including `/`). This is intentional: tracked_sources
# globs are an inclusive heuristic, not a strict matcher.
matches_pattern() {
    _path=$1
    _pattern=$2
    # Normalize ** -> *.
    _pattern=$(printf '%s' "$_pattern" | sed 's:\*\*:\*:g')
    case "$_path" in
        $_pattern) return 0 ;;
    esac
    return 1
}

# Strip leading/trailing whitespace and surrounding quotes from a value.
trim_value() {
    printf '%s' "$1" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
                          -e 's/^"//' -e 's/"$//' \
                          -e "s/^'//" -e "s/'\$//"
}

# Parse one frontmatter block; emit the concept name and tracked sources.
# Output lines:
#   CONCEPT <name>
#   SOURCE <path-or-glob>
#
# We only read the first `---`/`---` block. Lines after the second `---`
# are ignored. Inside `tracked_sources:` we accept either an inline
# (`tracked_sources: []`) or a YAML list (lines starting with `- `).
parse_frontmatter() {
    awk '
        BEGIN {
            in_fm = 0
            fm_seen = 0
            in_sources = 0
        }
        /^---[[:space:]]*$/ {
            if (fm_seen == 0) {
                in_fm = 1
                fm_seen = 1
                next
            } else if (in_fm == 1) {
                in_fm = 0
                exit
            }
        }
        in_fm == 1 {
            line = $0

            # Match top-level keys (no leading whitespace).
            if (line ~ /^[A-Za-z_][A-Za-z0-9_]*:/) {
                in_sources = 0
            }

            if (line ~ /^concept:[[:space:]]*/) {
                value = line
                sub(/^concept:[[:space:]]*/, "", value)
                # Strip trailing comments.
                sub(/[[:space:]]+#.*$/, "", value)
                # Strip surrounding quotes.
                sub(/^"/, "", value); sub(/"$/, "", value)
                sub(/^\047/, "", value); sub(/\047$/, "", value)
                if (length(value) > 0) print "CONCEPT " value
                next
            }

            if (line ~ /^tracked_sources:[[:space:]]*/) {
                rest = line
                sub(/^tracked_sources:[[:space:]]*/, "", rest)
                # Strip comments after a value.
                sub(/[[:space:]]+#.*$/, "", rest)
                # Inline `[]` or `[a, b]` form.
                if (rest ~ /^\[/) {
                    inner = rest
                    sub(/^\[/, "", inner)
                    sub(/\][[:space:]]*$/, "", inner)
                    n = split(inner, items, ",")
                    for (i = 1; i <= n; i++) {
                        v = items[i]
                        gsub(/^[[:space:]]+|[[:space:]]+$/, "", v)
                        sub(/^"/, "", v); sub(/"$/, "", v)
                        sub(/^\047/, "", v); sub(/\047$/, "", v)
                        if (length(v) > 0) print "SOURCE " v
                    }
                    in_sources = 0
                } else {
                    in_sources = 1
                }
                next
            }

            if (in_sources == 1) {
                # Continuation of a YAML block list. Lines start with optional
                # whitespace, then "- ", then the value (possibly quoted).
                if (line ~ /^[[:space:]]*-[[:space:]]+/) {
                    value = line
                    sub(/^[[:space:]]*-[[:space:]]+/, "", value)
                    sub(/[[:space:]]+#.*$/, "", value)
                    sub(/^"/, "", value); sub(/"$/, "", value)
                    sub(/^\047/, "", value); sub(/\047$/, "", value)
                    if (length(value) > 0) print "SOURCE " value
                    next
                }
                # If we hit a non-list, non-blank, non-indented line, the
                # block is over.
                if (line !~ /^[[:space:]]*$/) {
                    in_sources = 0
                }
            }
        }
    ' "$1"
}

# Iterate concept files. Skip files starting with `_` (meta: _index.md, _template.md).
warning_count=0

for concept_file in "$CONCEPTS_DIR"/*.md; do
    [ -f "$concept_file" ] || continue
    base=$(basename "$concept_file")
    case "$base" in
        _*) continue ;;
    esac

    fm=$(parse_frontmatter "$concept_file")
    [ -n "$fm" ] || continue

    concept_name=$(printf '%s\n' "$fm" | awk '$1=="CONCEPT" { $1=""; sub(/^[[:space:]]+/,""); print; exit }')
    if [ -z "$concept_name" ]; then
        # No `concept:` field; skip.
        continue
    fi

    # Was the concept file itself changed?
    concept_changed=0
    printf '%s\n' "$CHANGED_FILES" | while IFS= read -r f; do
        [ "$f" = "$concept_file" ] && exit 42
    done
    if [ $? -eq 42 ]; then
        concept_changed=1
    fi

    if [ "$concept_changed" -eq 1 ]; then
        continue
    fi

    # Was the concept verified by trailer?
    if [ -n "$VERIFIED_CONCEPTS" ]; then
        if printf '%s\n' "$VERIFIED_CONCEPTS" | grep -Fxq "$concept_name"; then
            continue
        fi
    fi

    # Walk tracked sources and look for a match against the changed file set.
    sources=$(printf '%s\n' "$fm" | awk '$1=="SOURCE" { $1=""; sub(/^[[:space:]]+/,""); print }')
    [ -n "$sources" ] || continue

    matched_change=""
    matched_pattern=""

    # Disable pathname expansion before iterating over patterns so that
    # `*` / `**` in tracked_sources are not expanded against the working
    # tree by the shell.
    set -f
    _saved_ifs=$IFS
    IFS='
'
    for pattern in $sources; do
        [ -n "$pattern" ] || continue
        for changed in $CHANGED_FILES; do
            [ -n "$changed" ] || continue
            if matches_pattern "$changed" "$pattern"; then
                matched_change=$changed
                matched_pattern=$pattern
                break
            fi
        done
        [ -n "$matched_change" ] && break
    done
    IFS=$_saved_ifs
    set +f

    if [ -n "$matched_change" ]; then
        printf '⚠ Concept %s may be stale: %s matches tracked_sources pattern %s but %s was not modified.\n' \
            "'$concept_name'" "$matched_change" "$matched_pattern" "$concept_file"
        warning_count=$((warning_count + 1))
    fi
done

if [ "$warning_count" -gt 0 ]; then
    printf '\nvalidate-concepts: %d warning(s). Validator informs; never blocks.\n' "$warning_count"
fi

exit 0
