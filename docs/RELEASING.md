# Releasing AMPERE

A release is one action: **pushing a `v*` tag**. The tag triggers
[`.github/workflows/publish.yml`](../.github/workflows/publish.yml), which
publishes eight modules to Maven Central and cuts a GitHub Release.

Maven Central is **permanent**. A published coordinate can never be replaced or
withdrawn, so the tag push is the point of no return — everything before it is
reversible and everything after it is not. That is what step 2 is for.

Work through the steps in order.

| Step | Command | Proceed only if |
|------|---------|-----------------|
| 1. Pick the commit | `git fetch origin && git log --oneline -1 origin/main` | CI is green on it |
| 2. Pre-flight | `scripts/release-preflight.sh <version>` | it prints `PASS` |
| 3. Tag and push | `git tag v<version> <commit> && git push origin v<version>` | — |
| 4. Watch | `gh run watch --exit-status $(gh run list --workflow=publish.yml -L1 --json databaseId -q '.[0].databaseId')` | it succeeds |
| 5. Bump `main` | `scripts/bump-version.sh <next-version>` + PR | — |
| 6. Confirm | see [step 6](#step-6--confirm-the-artifacts-landed) | artifacts are listed |

## How the version moves

**`main` always carries the next, unreleased version.** This is the single most
common source of confusion, so read the timeline rather than guessing:

| When | `ampereVersion` on `main` | What happened |
|------|---------------------------|---------------|
| v0.14.0 tagged | `0.14.0` | tag cut from a commit declaring `0.14.0` |
| two days later (#755) | `0.15.0` | `main` bumped to the *next* version |
| …54 commits of feature work… | `0.15.0` | every commit declares the unreleased `0.15.0` |
| v0.15.0 tagged | `0.15.0` | tag cut from one of those commits |
| after that (#863) | `0.16.0` | `main` bumped again |

Two consequences, both load-bearing:

- **The version to release is the one already in `gradle.properties`** — you do
  not bump before tagging. If you think you need to bump to release, you are
  about to release the wrong number.
- **The tag must point at a commit from before the next bump landed.**
  `publish.yml` re-reads `gradle.properties` out of the *tagged* tree and
  refuses a mismatch, so a tag placed after the bump fails validation. Tagging
  an older commit is always legal; `v<version>` and `ampereVersion` simply have
  to agree at that commit.

### The two files that carry the version

| File | Declaration |
|------|-------------|
| `gradle.properties` | `ampereVersion=0.16.0` |
| `ampere-core/src/commonMain/kotlin/link/socket/ampere/AmpereVersion.kt` | `const val AMPERE_RUNTIME_VERSION: String = "0.16.0"` |

They must be equal. `verifyAmpereVersionConstant` in
`ampere-core/build.gradle.kts` fails the build when they drift, and it is wired
into `:ampere-core:jvmTest`, so the drift surfaces as a red PR rather than as a
host that rejects bundles pinned to the version it actually is (AMPR-365).

Never edit them by hand — `scripts/bump-version.sh` edits both and verifies the
result. The constant was added after 0.15.0, so every bump commit older than
that touches only one file; do not copy their shape.

## Step 1 — pick the commit

```bash
git fetch origin
git log --oneline -1 origin/main
gh run list --branch main -L 3
```

Releases are cut from `main`. Confirm CI is green **on that exact SHA**, then
check it out so the pre-flight validates the tree you are about to tag:

```bash
git checkout <commit>
```

## Step 2 — pre-flight

```bash
scripts/release-preflight.sh 0.15.0
```

Pass the version you intend to release. The script refuses to go further unless
it matches `gradle.properties`, the two version declarations agree, the tree is
clean, and no such tag exists yet; then it runs one Gradle build.

**Do not skip this and lean on PR CI.** CI does not run
`:ampere-core:compileCommonMainKotlinMetadata`, so a JVM-only stdlib call in
`commonMain` compiles on the JVM target, passes CI, merges, and then fails the
publish job — which is what happened to v0.5.0, after its tag had been pushed.
CI also assembles only `ampere-core`, `ampere-cli`, `ampere-compose`,
`ampere-eval` and `ampere-work-linear`; `ampere-phosphor`,
`ampere-core-test-fixtures` and both `ampere-bindings-*` modules publish without
any CI job compiling them, and a red one of those fails publish *partway*, with
earlier modules already on Central.

If the pre-flight fails: fix it on a normal PR, merge, and start again from step
1 on the new `main`. Do not tag a tree you had to patch locally.

## Step 3 — tag and push

```bash
git tag v0.15.0 <commit>
git push origin v0.15.0
```

Lightweight tags, matching every release so far. Pushing the tag is the release.

## Step 4 — watch the publish

```bash
gh run watch --exit-status $(gh run list --workflow=publish.yml -L1 --json databaseId -q '.[0].databaseId')
```

Roughly 20–30 minutes on a macOS runner. In order, the job validates the tag
against `gradle.properties`, runs `ktlintCheck verifyPublishWorkflowCoverage`,
runs `:ampere-core:jvmTest`, decodes the signing key, publishes all eight
modules, then creates the GitHub Release with generated notes.

A failure *before* the publish step means nothing was published: fix forward,
delete the tag (`git push origin :refs/tags/v0.15.0`), and start again. A
failure *during* the publish step may have published some modules already —
treat the version as burned and release the next patch instead.

## Step 5 — bump `main` for the next cycle

```bash
git checkout -b bump-version-0.16.0 origin/main
scripts/bump-version.sh 0.16.0
git commit -am "Bump ampereVersion to 0.16.0"
gh pr create --base main --title "Bump ampereVersion to 0.16.0"
```

Minor bump for a normal release; patch only for a release that is itself a
fix on top of a published version. AMPERE is pre-1.0, so breaking changes go in
a minor.

Nothing else belongs in this PR — no release notes, and
[no changelog entry](../AGENTS.md#changelog): release notes are generated from
history at tag time, and a `CHANGELOG.md` would conflict across every parallel
PR. Expect the iOS CI job on this PR to be slow: the Kotlin/Native cache key
hashes `gradle.properties`, so editing it guarantees a cache miss.

## Step 6 — confirm the artifacts landed

Central takes up to ~30 minutes to index.

- <https://central.sonatype.com/publishing> — publishing status
- <https://repo1.maven.org/maven2/link/socket/> — the artifacts themselves
- `gh release view v0.15.0`

## What gets published

Eight modules, all at `ampereVersion`, under the `link.socket` group:

| Module | Coordinate |
|--------|------------|
| `ampere-core` | `link.socket:ampere-core` |
| `ampere-core-test-fixtures` | `link.socket:ampere-core-test-fixtures` |
| `ampere-phosphor` | `link.socket:ampere-phosphor` |
| `ampere-bindings-android` | `link.socket:ampere-bindings-android` |
| `ampere-bindings-apple` | `link.socket:ampere-bindings-apple` |
| `ampere-cli` | `link.socket:ampere-cli` |
| `ampere-eval` | `link.socket:ampere-eval` |
| `ampere-work-linear` | `link.socket:ampere-work-linear` |

The list lives in `publish.yml`'s run command. A module that configures
`mavenPublishing { }` but is missing from that command publishes nothing, with
no build failure to say so — which is how `ampere-core-test-fixtures` missed two
whole releases. `verifyPublishWorkflowCoverage` (root `build.gradle.kts`) now
fails the build on that drift, and `release-preflight.sh` additionally refuses a
publishing module that no CI job compiles. Both run in the pre-flight.

## Rules

- **Never force-move or delete a tag whose publish step has started.** Central
  artifacts outlive the tag; a moved tag makes the two disagree forever.
- **Never leave a `-SNAPSHOT` suffix on `main`.** `publish.yml` compares the tag
  to `ampereVersion` as an exact string. An earlier version of this document
  recommended a snapshot suffix for the next cycle; no release has ever done it,
  and it would break the following tag's validation.
- **Never hand-edit one of the two version files.** Use `bump-version.sh`.
- **Never run the pre-flight as several `./gradlew` calls.** On a constrained
  machine the build admission hook allows one build at a time and refuses the
  rest; add tasks to the script's list instead.

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Tag version (X) does not match gradle.properties (Y)` | tag placed after the next-cycle bump, or on the wrong branch | delete the tag, re-tag a commit where `ampereVersion` is X |
| `AMPERE_RUNTIME_VERSION is "X" but gradle.properties declares Y` | hand-edited one of the two files | `scripts/bump-version.sh Y` |
| publish fails in `compileCommonMainKotlinMetadata` | JVM-only API used in `commonMain`; PR CI cannot see it | fix on a PR, then release again from the new `main` |
| publish fails in a module PR CI never compiles | the CI/publish coverage gap | fix on a PR; the pre-flight would have caught it |
| `Signature verification failed` | GPG public key not on the keyserver | `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>` |
| Nothing on Central after 30 min | Portal lag or a validation hold | check <https://central.sonatype.com/publishing> |
| POM validation failed | missing POM metadata | `./gradlew :ampere-core:generatePomFileForKotlinMultiplatformPublication` and read `ampere-core/build/publications/kotlinMultiplatform/pom-default.xml` |

## Appendix A — one-time setup

Needed once per machine or per repository, not per release.

### Maven Central Portal account

1. Create an account at <https://central.sonatype.com>.
2. Register the `link.socket` namespace.
3. Generate a user token at <https://central.sonatype.com/account>.

### GPG key

```bash
# RSA 4096, no expiration for release keys
gpg --full-generate-key

# The key ID is the last 8 characters of the long ID
gpg --list-secret-keys --keyid-format LONG

# Maven Central requires the public key to be discoverable
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
```

### Local credentials

In `~/.gradle/gradle.properties` — never in the repository, and never in
`local.properties`:

```properties
mavenCentralUsername=your-token-username
mavenCentralPassword=your-token-password
signingInMemoryKeyId=ABCD1234
signingInMemoryKey=exported-ascii-armored-key
signingInMemoryKeyPassword=your-gpg-passphrase
```

### GitHub secrets

| Secret | Value |
|--------|-------|
| `MAVEN_CENTRAL_USERNAME` | Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Portal token password |
| `SIGNING_KEY_ID` | last 8 characters of the GPG key ID |
| `SIGNING_KEY` | `gpg --armor --export-secret-keys YOUR_KEY_ID \| base64` |
| `SIGNING_PASSWORD` | GPG passphrase |

## Appendix B — rehearsals and manual publishing

**Dry run on CI.** `gh workflow run publish.yml -f dry_run=true` runs
`ktlintCheck`, `verifyPublishWorkflowCoverage` and `:ampere-core:jvmTest` for
real on a macOS runner, then calls the publish task with `--dry-run`. Note that
`--dry-run` only prints Gradle's task graph: it proves the publish wiring
resolves, and compiles and signs nothing. It is a complement to
`release-preflight.sh`, not a substitute.

**Publishing by hand.** Only when the workflow itself is broken and the tag is
already pushed. Publish every module, or the release is partial:

```bash
./gradlew \
  :ampere-core:publishAllPublicationsToMavenCentralRepository \
  :ampere-core-test-fixtures:publishAllPublicationsToMavenCentralRepository \
  :ampere-phosphor:publishAllPublicationsToMavenCentralRepository \
  :ampere-bindings-android:publishAllPublicationsToMavenCentralRepository \
  :ampere-bindings-apple:publishAllPublicationsToMavenCentralRepository \
  :ampere-cli:publishAllPublicationsToMavenCentralRepository \
  :ampere-eval:publishAllPublicationsToMavenCentralRepository \
  :ampere-work-linear:publishAllPublicationsToMavenCentralRepository \
  -PmavenCentralUsername=YOUR_TOKEN_USERNAME \
  -PmavenCentralPassword=YOUR_TOKEN_PASSWORD \
  -PsigningInMemoryKeyId=KEY_ID \
  -PsigningInMemoryKey="$(gpg --armor --export-secret-keys KEY_ID)" \
  -PsigningInMemoryKeyPassword=KEY_PASSPHRASE
```

This is a full multiplatform publish from one machine, including every Apple
target. It needs macOS and a lot of memory.

## Version numbering

[Semantic Versioning](https://semver.org/), pre-1.0:

- **MAJOR** — reserved; AMPERE is on `0.x`.
- **MINOR** — features, and breaking API changes while pre-1.0.
- **PATCH** — fixes on top of a published version.
