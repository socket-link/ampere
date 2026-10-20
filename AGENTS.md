# AGENTS.md

Ampere is a transparent cognitive engine built in Kotlin Multiplatform, where AI reasoning is visible by design. Read [SOUL.md](SOUL.md) for project philosophy and values.

## Concepts

This repo's load-bearing primitives are documented as concept cells in
[`docs/concepts/`](docs/concepts/). The index is at
[`docs/concepts/_index.md`](docs/concepts/_index.md).

**Before starting any task:**
- Read `docs/concepts/_index.md`.
- For any concept whose `tracked_sources` include files you will touch, read
  that concept file in full before writing code. Treat the `Invariants` and
  `Anti-patterns` sections as binding.

**When your changes touch `tracked_sources`:**
- Re-read the concept file before opening the PR.
- If your changes *confirm* the concept as written, update `last_verified` to
  today's date. No other change required.
- If your changes *contradict* the concept (new invariant, retired one,
  changed file path, new common operation), update the relevant sections.
- If you discover a wrong assumption you almost made, add it to
  `Anti-patterns` with one sentence of reasoning.
- The concept file update is part of your diff. Do not split it into a
  follow-up PR.

**Commit trailers:**
- When a commit touches `tracked_sources` for a concept and you have verified
  the concept is still accurate without changing the file, include the trailer
  `Concept-Verified: <ConceptName>` in the commit message. This satisfies
  the validator without requiring a separate `last_verified` bump.

## Ticket Authoring

Tickets here are dispatched by machine, so a ticket is only as dispatchable as
its metadata. Before filing one, read
[`docs/concepts/ticket-conventions.md`](docs/concepts/ticket-conventions.md) —
the seven conventions, each with a filled example — and copy
[`docs/templates/ticket-description.md`](docs/templates/ticket-description.md)
for the description body.

The two most often missed:

- **An `ampere-scope` block** in the description, declaring every repository
  and repo-relative path glob the ticket may write. A ticket with no block has
  *unknown* scope, not empty scope, so it can only be dispatched alone.
- **Exactly one `wave:<id>` label.** It is the only supervisory fact the work
  source can filter server-side; a ticket without it is in no ready queue.

## Ticket Delivery Loop

Tickets are worked by many agents at once, each in its own Conductor workspace, and Miley is not
watching any one of them. So an agent carries its ticket all the way to a green PR, and speaks up
only when a person is actually needed. If you were started some other way (an Ampere dispatch, a
plain CLI session) and your prompt says otherwise, the prompt wins.

**You are finished** when there is a PR against `main` with every required check green, and every
other check green or explained in the PR. Until then, the only thing that pauses the loop is a
question to Miley, asked with Conductor's question tool (see "When you need Miley"). Ending your
turn any other way (CI still running, a red check you haven't read, a question left in your
closing message) strands the ticket, because nobody else is watching it.

Recon and spike tickets end differently. They have no PR, and their write-up goes on the Linear
ticket as comments, never into the repo (drafting it in `.context/` first is fine). They are
finished when the findings are posted and the ticket's STOP-gate question, if it has one, is asked.

### The loop

1. **Read the ticket.** It lives on Linear as `AMPR-<n>`. Its "synced to a corresponding GitHub
   issue" line gives the GitHub number that commits and the PR need. Read the concept file for
   anything you will touch (see "Concepts"). Its `ampere-scope` block lists the path globs the
   ticket may write: stay inside them. Work that has to write outside them is a reason to ask,
   not a reason to widen the diff. A ticket with no block has unknown scope, so keep the diff to
   what the ticket names.
2. **Do the work, with tests, without building.** Builds are the scarce resource when a dozen
   agents share one machine. Check your work by reading code, and build once, at the end.
3. **Validate once, narrowly.** `.ampere/verify.yml` is this repo's declared definition of done:
   one gate per command, the same commands CI runs. Pick the gates your change can reach and run
   their narrowest form in **one** Gradle invocation, with `ktlintFormat` in front (see
   "Testing"), for example
   `./gradlew ktlintFormat :ampere-core:jvmTest --tests "*FieldService*"`. CI runs every gate on
   every push. The local pass is there to catch the obvious before a 30-minute round trip, not to
   prove every target. If the machine refuses a local build for lack of memory, don't retry and
   don't work around it. Push, let CI verify, and say so under Verification in the PR.
4. **Commit and push** the workspace branch, following "Commit and PR conventions". Then run
   `scripts/validate-concepts.sh`: it diffs `origin/main..HEAD`, so it says nothing about
   uncommitted work. Fix what it reports with an amend before you open the PR.
5. **Open the PR** with `gh pr create --base main`, ready for review rather than draft. If
   `gh pr create` errors or hangs, run `gh pr list --head "$(git branch --show-current)"` before
   you retry. GitHub sometimes creates the PR and then drops the connection, and a retry opens a
   duplicate.
6. **Wait on CI** with `scripts/pr-checks.sh`. Run it in the foreground (in Claude Code, with the
   Bash tool's `timeout` at its `600000` maximum). It polls quietly, returns within nine minutes,
   and its exit code says what to do next:

   | Exit | Meaning | Next |
   |---|---|---|
   | `0` | Every required check passed; every other check has finished | Read any red it lists, then step 8 |
   | `1` | A check is red, or a required one can no longer run | Step 7 |
   | `3` | The PR conflicts with `main`, so CI cannot run | Rebase (below), then step 6 |
   | `8` | Still running | Run it again |

   Don't end your turn while it returns `8`. Spend the wait on work that doesn't depend on the
   result, like the PR body or a concept update. A full run takes about half an hour, most of it
   the iOS job.

   The three required checks are `JVM + Android Tests (ubuntu, JDK 21)`,
   `iOS Simulator Tests (macos, JDK 21)` and `check`. The first two are aggregators: they run
   `if: always()` after the jobs they gather (`JVM Lint + Build`, the five
   `JVM + Android Tests (<module>, JDK 21)` jobs and `Eval Replay Gate` for the first,
   `iOS Tests (macos, JDK 21)` for the second), report only when all of those have finished, and
   their own logs just name the failed job. So the script stops at the first red of any kind,
   required or not, and points you at the job that failed rather than the aggregator.
   `iOS Tests` is skipped for a change that touches only `docs/`, `scripts/` or `*.md` files, and
   its aggregator counts the skip as a pass. `check` is the concept staleness validator: it never goes
   red, and posts its warnings as a sticky PR comment instead. Read that comment.
7. **Fix and go again.** Read the failed job's log with
   `gh api repos/{owner}/{repo}/actions/jobs/<id>/logs --allow-escape-sequences` (the script
   prints the exact command). `gh run view --log-failed` refuses until the whole run has finished,
   and the script reports a red as soon as it happens. Decide whether the failure is your diff,
   `main`, or the infrastructure (see "Reading a red check"). Fix it, re-run the narrow local
   check that covers the fix, push, and go back to step 6. A push to a PR cancels the run it
   supersedes and starts the whole matrix again, so don't also re-run jobs by hand. Reds on
   checks that aren't required are yours to read too: fix one your diff caused, and explain in
   the PR one it didn't.
8. **Finish.** If the work changed along the way, update the PR body to match. Then close with
   a short message: the PR link, the script's verdict line, what was verified locally and what
   only in CI, and anything a person still owes. Don't merge; Miley merges. A green PR that
   GitHub shows as `BLOCKED` is waiting on the "Protect Main" ruleset's review, not on CI. If you
   are asked to merge, use `gh pr merge --squash`, which is how `main`'s history is kept.

**Rebasing.** When `main` moves and the PR conflicts, run
`git fetch origin main && git rebase origin/main`. Use `origin/main`, never the local `main`,
which is shared across workspaces and stale. Resolve the conflicts, and check that
`git diff --check origin/main` reports no leftover conflict markers. Re-run the narrow local
check, then `git push --force-with-lease`.

**Know when to stop pushing.** If the same check is still red after three fix attempts, or it is
red and you can't tie it to your diff or to a known infrastructure failure, stop and ask (see
"When you need Miley").

### Reading a red check

Not every red is the diff. Before you change code for a failure, check whether `main` shows the
same red (`gh run list --workflow ci.yml --branch main --limit 3`). If it does, the break isn't
yours. Rebase if a newer `main` commit fixes it. Otherwise don't fix it inside your PR: say so in
the PR and ask Miley, who may already have it in hand. Then compare the failure with the table
below. Re-run an infrastructure failure once: `gh run rerun <run-id> --failed`, or
`--job <job-id>` for a single job. GitHub refuses both while any job in the run is still going. A
second identical failure is no longer a flake. Never edit code to make an infrastructure failure
go away. macOS runner concurrency is this repo's scarce CI resource (five jobs org-wide, see
`ci.yml`), so re-run the iOS job only with a reason, never as a reflex.

Sorted from the 50 failed `ci.yml` runs of 2026-07-29 to 2026-10-20: about 31 were the diff, at
least 15 were red because `main` was, and 9 were infrastructure.

| What you see | What it is | What to do |
|---|---|---|
| An aggregator (`JVM + Android Tests (ubuntu, JDK 21)`, `iOS Simulator Tests (macos, JDK 21)`) is red, and its log only says `jvm-test: skipped` or `ios-tests: failure` | Never the cause. Every test job shows `skipped` when `JVM Lint + Build` failed | Read the job it names (`pr-checks.sh` already points there) |
| A run is `cancelled`, or a `main` run has zero jobs | A newer push replaced it. Not a failure | Nothing |
| `JVM Lint + Build`: `KtLint found code style violations`, with `…kt:L:C <message> (standard:<rule>)` lines above `What went wrong` | ktlint, run before any test job | Run `./gradlew ktlintFormat` and push. Grep the log for `(standard:`; the many `SourceSetCheck SKIPPED` lines are noise |
| `e: … 'when' expression must be exhaustive. Add the 'is <NewEvent>' branch` | A new `Event` subtype missed an exhaustive `when`; two of the sites are in `ampere-cli` | Add the branch everywhere the compiler names, not only in `ampere-core` |
| The same `e: …/commonMain/…` line in `compileCommonMainKotlinMetadata`, JS, Android and iOS compiles | A `commonMain` compile error that the JVM compile may not show | Fix the first `e:` line |
| `compileKotlinIosSimulatorArm64 FAILED` or `compileTestKotlinIosSimulatorArm64 FAILED` with an `e:` line | An iOS-only compile error, often a comma in a backticked `commonTest` name | Fix it, and check with `:ampere-core:compileTestKotlinIosSimulatorArm64` |
| `<Test>[jvm] > … FAILED`, or `:<module>:compileTestKotlinJvm FAILED` with an `e: …/jvmTest/…` line | A test or test-compile failure: the diff, or `main` | Check `main` first. Then reproduce with `:<module>:jvmTest --tests "*<Test>*"` |
| `initializationError[jvm] FAILED` with `InvalidTestClassError` | A JUnit 4 test that doesn't return `Unit` | `runBlocking<Unit>` |
| `SerializationException at AbstractPolymorphicSerializer.kt`, or on iOS `Serializer for subclass '<X>Event' is not found in the polymorphic scope of 'Event'` with `Executed 0 tests` | A subtype not registered for serialization, or a field type that isn't serializable. The JVM tests can still pass | Register the subtype; check its field types |
| `Could not resolve project :ampere-core-test-fixtures`, or `No matching variant` | A module lacks a target its consumer builds | Add the target or narrow the dependency |
| Swift build: `value of type 'any Kotlinx_coroutines_coreStateFlow' has no member 'value'`, then `** TEST BUILD FAILED **` | A Kotlin member named `value` on an interface or sealed class renamed `StateFlow.value` in `shared.h` | Rename the member; only a final class is safe |
| `shared.h:N: error: expected member name` at `TRUE`, `FALSE`, `YES`, `NO` or `NULL` | A Kotlin name collides with an Objective-C macro | Suffix the name |
| Swift build: `has no member '<x>'` for an API new in iOS 27 | CI builds with Xcode 26.6 and the iOS 26.5 SDK, even inside `#available` | Wrap it in `#if compiler(>=6.4)` |
| Other Swift `error:` lines (`cannot find type …`, `'Any?' has no member …`) | The Swift side no longer matches how the Kotlin change exports | Read the linked `shared.h` and fix the Swift |
| `error: -[ampere_iosTests.<Class> <test>] : XCTAssert… failed`, `** TEST EXECUTE FAILED **` | A Swift bridge test failed. CI runners report on-device generation available but cannot generate, so a test that trusts availability fails here | Fix the test; gate on-device cases on a real generation |
| `Could not GET 'https://repo.maven.apache.org/…'. Received status code 429` | Maven Central rate limiting | Infrastructure. Re-run the job once |
| `JVM Lint + Build`: `has timed out after 20 minutes`, last task `:ampere-core:compileProductionExecutableKotlinWasmJs`, no `FAILED` | The wasm production build stalling, seen on `main` too | Infrastructure. Re-run once; if it repeats, ask |
| `ArcTraceProjectionTest … within target budget FAILED` | A 300 ms timing budget missed on a loaded runner | Infrastructure. Re-run once; don't widen the budget |
| `'Run iOS simulator tests' has timed out after 25 minutes` after `linkDebugTestIosSimulatorArm64` | A slow or stuck native link | Infrastructure. Re-run once (macOS is scarce) |
| `xcodebuild: error: Unable to find a device matching the provided destination specifier` | The simulator choice doesn't match the runner image | Infrastructure. Re-run once; if it repeats, ask |

No runner, billing or zero-step failure has happened here yet. If `pr-checks.sh` prints
`never started`, it is one of those, and only Miley can fix it, so ask.

To grep a finished job's log, even while the rest of the run is going, use
`gh api repos/{owner}/{repo}/actions/jobs/<id>/logs --allow-escape-sequences | grep -nE 'FAILED|What went wrong| e: |##\[error\]'`.
Every log line starts with a timestamp, so anchor nothing on `^`: ` e: ` is how a Kotlin compile
error reads.

### When you need Miley

Ask with Conductor's question tool: `AskUserQuestion` from the Conductor MCP server
(`mcp__conductor__AskUserQuestion` in Claude Code). It notifies Miley and holds your session until
the answer arrives. A question left in your closing message is easy to miss among a dozen
workspaces that all look finished. Without a Conductor tool (an Ampere dispatch, a plain CLI
session), put the question first in your closing message and stop.

**Ask when:**
- the ticket is ambiguous or contradicts the code, and the readings lead to materially different
  work;
- the work has to write outside the ticket's `ampere-scope` globs;
- the step is destructive, or involves credentials, production configuration, billing, or legal
  or privacy risk;
- something only Miley can change is in the way: Actions billing, branch protection or the
  "Protect Main" ruleset, CI workflows, repository secrets, Apple signing, Conductor settings, or
  a release (see "Release");
- the ticket reserves a step for a person, such as a device pass, a STOP gate, or a review it
  names Miley for. If that step doesn't block the PR, ask once the PR is green, so the question
  can carry the PR link;
- the loop is stuck: the same check is red after three fix attempts, or a red you can't
  attribute.

**Don't ask** for permission to run the loop (committing, pushing, opening the PR, fixing CI,
re-running an infrastructure failure). Don't ask Miley to choose between options when one is
clearly better: pick it, and record the choice and the reason under "Deviations from the ticket"
in the PR. And don't ask whether there's anything else to do.

**How to ask:**
- Make each question stand alone. Miley may be answering from another workspace with none of your
  context. State what you found in a sentence or two, with the `file:line` or the job link, and
  what each answer would lead you to do.
- Offer the options as choices, with your recommendation first and labelled "(Recommended)".
- Put every open decision into one call (the tool takes up to four questions) rather than asking
  them one at a time.
- Before you ask, finish and push everything that doesn't depend on the answer, so the question
  is the only thing left.

### Commit and PR conventions

These apply from the first commit, because a pushed commit can't be fixed without a force-push.
They live here rather than in Conductor's create-PR prompt, which only fires on its button.

- **Titles** carry both ticket IDs: `AMPR-<n> #<GitHub issue>: <what changed>`. A PR that
  closes several tickets lists each pair, comma-separated:
  `AMPR-469 #1044, AMPR-477 #1057: <what changed>`. Commits and the PR use the same shape, merge
  commits included. A ticket with no synced GitHub issue drops the `#<n>`.
- **Commit and PR bodies** end with `Authored by Claude (<model>), not by the committer.` (or your
  own agent's name), followed by any attribution trailer your harness adds. Every commit's git
  author is Miley, so the body is the only place that shows who wrote it.
- **The PR body** opens with one `Closes #<GitHub issue>` line per issue the PR completes, or
  `Refs #<n>` for one it doesn't. Then it covers what was wrong, the change, deviations from the
  ticket and why, and Verification: which `.ampere/verify.yml` gates ran locally (by id), which
  were left to CI and why, and anything a person still owes.
- **Socket follow-ups.** Socket consumes Ampere as a Maven artifact, so an Ampere PR changes
  nothing in Socket until a release and a version bump there. When the ticket exists for
  Socket's sake, say in the PR which Socket follow-up it unblocks.
- **Concept trailer.** See "Commit trailers" under "Concepts".
- **No changelog entry** (see "Changelog").

Work without a ticket drops the IDs and keeps the rest.

## Changelog

There is no `CHANGELOG.md`. Do not create one, and do not add a changelog entry
as part of a PR — every parallel PR editing the same "Unreleased" section
guarantees merge conflicts and resets CI on unrelated branches. Release notes
are generated at release time from commit/PR history instead.

## Development Commands

### Build & Test
```bash
./gradlew build                        # Build the project (CI's job, not a local habit; see "Testing")
./gradlew allTests                     # Run all tests
./gradlew jvmTest                      # Run JVM-specific tests in every module (CI's gate; run one module locally)
./gradlew iosSimulatorArm64Test        # Run iOS tests (Apple Silicon simulator)
./gradlew run                          # Run desktop application
./gradlew installDebug                 # Run Android app
./gradlew package                      # Package desktop app for distribution
```

### Code Quality
```bash
./gradlew ktlintFormat                 # Auto-format code
./gradlew ktlintCheck                  # Check formatting
./gradlew lint                         # Android lint
./gradlew dokkaHtml                    # Generate API documentation
```

### Release
```bash
scripts/release-preflight.sh 0.15.0    # Gate a release, on the commit you intend to tag
scripts/bump-version.sh 0.16.0         # Move both version declarations together
```

Publishing is one action — pushing a `v*` tag — and Maven Central is permanent.
Work through [docs/RELEASING.md](docs/RELEASING.md) in order; never skip the
pre-flight, which covers what PR CI does not. `main` always carries the *next*,
unreleased version, so the number to release is the one already in
`gradle.properties` and the tag goes on a commit from before the next bump.

Agents never push tags and never run a `publish*` task, `publishToMavenLocal` included: Socket's
build resolves `mavenLocal()` on this Mac, so a local publish silently changes what every Socket
workspace compiles against. Don't run `scripts/bump-version.sh` or edit a version unless the
ticket is the bump. A ticket that needs a release is a reason to ask (see "When you need Miley").

### CLI
```bash
./gradlew :ampere-cli:installDist      # Build the CLI

# TUI dashboard
./ampere-cli/ampere                                    # Idle dashboard
./ampere-cli/ampere --auto-work                        # Start with background issue work
./ampere-cli/ampere --goal "Implement FizzBuzz"        # Custom goal
./ampere-cli/ampere --issues                           # Work on GitHub issues
./ampere-cli/ampere --issue 42                         # Work on specific issue

# TUI controls: d=dashboard, e=events, m=memory, v=verbose, ?=help, :=command mode, q=quit

# Other CLI commands
./ampere-cli/ampere thread list                        # View conversation threads
./ampere-cli/ampere status                             # System status dashboard
./ampere-cli/ampere reconcile                          # Recover from an interrupted supervisor
./ampere-cli/ampere outcomes stats                     # Execution outcome stats
./ampere-cli/ampere issues create -f epic.json         # Create issues from file
./ampere-cli/ampere test agent                         # Headless agent test
```

See [ampere-cli/README.md](ampere-cli/README.md) for full CLI documentation.

## Architecture at a Glance

Ampere follows a layered architecture built on the **PROPEL** cognitive loop (Perceive, Recall, Observe, Plan, Execute, Learn) and six core primitives (Tickets, Tasks, Plans, Meetings, Outcomes, Knowledge).

Paths are relative to `ampere-core/src/commonMain/kotlin/link/socket/ampere/`.

| Layer | Location | Purpose |
|-------|----------|---------|
| AI Providers | `domain/ai/` | Multi-provider support (Anthropic, OpenAI, Google) with fallback chains |
| Agent Definitions | `domain/agent/` | Agent identities, prompts, and bundled agent catalog |
| Cognitive Core | `agents/definition/`, `agents/domain/reasoning/` | PROPEL loop (`AutonomousAgent`, `SparkBasedAgent`), autonomous agent contracts, the per-phase reasoning services |
| Hosted Runs | `propel/` | `RunHost` / `HostedRun`: one consumer-opened PROPEL run over a roster of seats, behind a `PlanGate` (AMPR-393) |
| Arc Runtime | `domain/arc/` | Charge → Flow → Pulse, `AmpereRuntime`, `ArcSession` |
| Event System | `agents/events/` | EventBus, routing, persistence, messaging, escalation |
| Coordination | `agents/events/meetings/` | Standup, sprint planning, code review, ad-hoc meetings |
| Tools | `agents/execution/tools/` | WriteCode, RunTests, ReadCodebase, AskHuman |
| Tool Registry | `agents/tools/` | Registration and MCP server wiring (not the tool implementations) |
| Persistence | `data/` | SQLDelight repositories with observable state |
| UI | `ui/` | Compose Multiplatform screens and components |
| CLI | `ampere-cli/` | TUI dashboard, event streaming, thread management |

For details: [Core Concepts](docs/CORE_CONCEPTS.md) | [Agent Lifecycle](docs/AGENT_LIFECYCLE.md) | [Architecture](docs/ARCS.md)

## Key Paths

| Module | Purpose |
|--------|---------|
| `ampere-core/src/commonMain/` | Shared business logic (agents, events, domain, UI) |
| `ampere-core/src/jvmMain/` | JVM platform implementations (SQL drivers) |
| `ampere-core/src/androidMain/` | Android platform implementations |
| `ampere-core/src/iosMain/` | iOS platform implementations |
| `ampere-cli/` | Command-line TUI and tools |
| `ampere-work-linear/` | Work-source adapter: Chassis SPI Plug over MCP (ready queue, claim protocol, status lifecycle) |
| `ampere-android/` | Android application |
| `ampere-desktop/` | Desktop application (Compose) |
| `ampere-ios/` | iOS application (Xcode project) |
| `docs/` | Architecture and concept documentation |

## Before You Change Anything

- Read the file you're modifying. Read its tests. Read its callers.
- Check [docs/CORE_CONCEPTS.md](docs/CORE_CONCEPTS.md) if touching primitives (Tickets, Tasks, Plans, Meetings, Outcomes, Knowledge).
- Check [docs/AGENT_LIFECYCLE.md](docs/AGENT_LIFECYCLE.md) if touching the PROPEL loop.
- Check [docs/ARCS.md](docs/ARCS.md) if touching orchestration patterns.

## Kotlin Conventions

- Sealed classes/interfaces for closed type hierarchies
- Data classes for immutable value types
- `expect`/`actual` for platform-specific code
- Follow existing SQLDelight patterns for persistence
- ktlint with IntelliJ IDEA code style (auto-format with `./gradlew ktlintFormat`)

## Agent System Rules

- Every new event type needs: serializer, EventBus registration, CLI display handler
- Agent prompts belong in `AgentDefinition` subclasses, not loose strings
- Tool functions must handle errors gracefully and return structured results
- Confidence thresholds should be configurable, not hardcoded
- New agents go in `domain/agent/bundled/` following existing category organization

## Testing

- Test behavior, not implementation details
- Mock AI provider responses in tests; never call real APIs
- New features need tests in `commonTest`

**CI is the full gate; the local pass is one narrow run at the end.** Many agents share a 16 GB
Mac, and a whole-repo `jvmTest` after every edit, in every workspace, is what stalls it. Write the
change and its tests first, then run one Gradle invocation that covers them (see "The loop",
step 3). Fix what it reports and re-run once.

**Where tests live, and what runs them.** CI's matrix, and the matching `.ampere/verify.yml`
gates:

| Module | Task | Notes |
|---|---|---|
| `ampere-core` | `:ampere-core:jvmTest`, `:ampere-core:testDebugUnitTest` | `commonTest` runs under both, and on iOS |
| `ampere-cli` | `:ampere-cli:jvmTest` | |
| `ampere-compose` | `:ampere-compose:jvmTest`, `:ampere-compose:testDebugUnitTest` | |
| `ampere-eval` | `:ampere-eval:jvmTest`, `:ampere-eval:evalReplay` | `evalReplay` is the Eval Replay Gate |
| `ampere-work-linear` | `:ampere-work-linear:jvmTest` | |
| `ampere-core` on iOS | `:ampere-core:iosSimulatorArm64Test` | CI's macOS job; also builds the Swift bridge |

- Narrow with `--tests "*ClassName*"`. A filter that matches nothing fails the task with
  "No tests found for given includes", so check the class name before blaming the build.
- Prefer `:<module>:jvmTest` over `testDebugUnitTest` locally: the Android variant compiles the
  module again for the same `commonTest` sources.
- `commonTest` is also compiled for Kotlin/Native, where a backticked test name may not contain a
  comma. After a `commonTest` change, add `:ampere-core:compileTestKotlinIosSimulatorArm64` to the
  local run rather than waiting for the iOS job to say so.
- `commonTest` has no SQLite driver, so assertions about persisted events go in `jvmTest`.
- JUnit 4 rejects a non-`Unit` test: wrap a body in `runBlocking<Unit>` when its last
  expression returns a value.
- `./gradlew ktlintFormat` from the root formats every module that applies ktlint. `ampere-cli`
  and `ampere-compose` apply none, so `:ampere-cli:ktlintFormat` does not exist.
- A `jvmTest` that reports `FROM-CACHE` ran nothing. Add `--rerun` to the task when you need it
  to execute.

## Git Worktree Hazards (Conductor)

This repo is commonly checked out as multiple Conductor workspaces, which are
git worktrees sharing a single `.git` directory. Some refs and state are
**not** per-worktree and collide across concurrently running workspaces:

- **Never use `git stash`.** `refs/stash` is a single shared ref across all
  worktrees of the same repo. A concurrent stash from another workspace can
  interleave with yours and mix unrelated changes. Use `git commit` to a
  throwaway/WIP commit (and `git reset --soft HEAD^` to undo) instead of
  stashing.
- Be cautious with other shared, non-worktree-local git state (e.g. global
  config, hooks, `git gc`/`git prune` — these operate on the shared
  `.git` object store) when other workspaces may be active concurrently.
- Conductor names your branch, and may rename it mid-session. That is expected.
  Keep working on whatever branch is checked out, and don't rename it yourself.
- A workspace is cut from `origin/main` once and then drifts as other agents'
  PRs merge. Answer "is X on main?" from `origin/main` (`git fetch origin main`,
  then `git grep <pattern> origin/main`), not from your working tree.
- Act only on your own branch, PR, workflow runs and Actions caches. Other
  workspaces' are someone else's work in progress.

## Safety Boundaries

- **Never** modify `local.properties` (contains API keys)
- **Never** push directly to `main`
- **Never** push a tag or run a `publish*` task (see "Release")
- **Never** delete SQLDelight migration files
- **Never** commit API keys, tokens, or secrets
- **Always** run one narrow local validation pass before pushing, and leave the full matrix to CI
  (see "Testing")
- **Always** run `./gradlew ktlintFormat` before committing

## Permissions

- **File read:** any file in the repository
- **File write:** `src/`, `test/`, `docs/`, config files (not `local.properties`)
- **Git:** branch creation, commits, and pushing your workspace branch (`--force-with-lease`
  after a rebase). Never `main`, never a tag.
- **GitHub (`gh`):** open and update your own branch's PR; read and re-run its workflow runs and
  read their logs; delete its own Actions caches. Nothing else: no merges unless asked, no
  edits to branch protection, rulesets, workflows or secrets
- **Linear:** read the ticket you are working, comment on it, and move its status
- **Build/test:** full access to `./gradlew` commands, except `publish*` tasks
- **External:** no other network requests or API calls without explicit approval
