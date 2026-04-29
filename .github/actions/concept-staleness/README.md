# `concept-staleness` composite action

Run AMPERE's concept-cells staleness validator on a pull request and surface
warnings as a sticky PR comment. The action is informational only — the
underlying validator always exits `0`, so the workflow job never fails on a
warning.

## Usage in this repo

```yaml
# .github/workflows/concept-staleness.yml
name: Concept staleness
on:
  pull_request:
    branches: [main]
jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0   # required so we can diff against the base ref
      - uses: ./.github/actions/concept-staleness
        with:
          concepts-dir: docs/concepts
```

## Usage from a downstream repo

The action is a composite (no Docker, no Node container), so it is callable
directly via `uses:` from any GitHub-hosted runner without extra setup.
Downstream repos that vendor `validate-concepts.sh` to a different path can
override `script-path`.

```yaml
- uses: socket-link/ampere/.github/actions/concept-staleness@<ref>
  with:
    base-ref: ${{ github.base_ref }}
    concepts-dir: docs/concepts
    script-path: scripts/validate-concepts.sh
```

`<ref>` should be a tag or commit SHA. The action ships in this repo and is
versioned with it.

## Inputs

| Input            | Default                          | Purpose |
|------------------|----------------------------------|---------|
| `base-ref`       | `${{ github.base_ref }}` or `main` | Branch the diff is computed against. Resolved to `origin/<base-ref>` before passing to the validator. |
| `head-ref`       | `HEAD`                           | Head ref for the diff. |
| `concepts-dir`   | `docs/concepts`                  | Directory of concept files. |
| `script-path`    | `scripts/validate-concepts.sh`   | Path to the validator. Override when vendoring to a different location. |
| `comment-marker` | `<!-- concept-staleness-bot -->` | HTML comment marker used to identify and update the sticky comment. |

## Outputs

| Output             | Purpose |
|--------------------|---------|
| `warning-count`    | Number of staleness warnings the validator emitted. `0` on a clean run. |
| `validator-output` | Full validator stdout (multiline). |

## Behaviour

1. Resolves the base ref (input → `GITHUB_BASE_REF` → `main`).
2. Warns if the checkout looks shallow (i.e. you forgot `fetch-depth: 0`).
3. Fetches the base ref into `origin/<base-ref>` if it's not already present.
4. Runs the validator: `bash <script-path> --base origin/<base-ref> --head <head-ref> --concepts-dir <concepts-dir>`.
5. On `pull_request` events, posts (or updates in place) a sticky comment
   keyed by `comment-marker`. If there are no warnings, the comment body is
   reduced to a single "clean" line so it stays visible without nagging.

The action never sets `failure` on warnings. To gate a merge on staleness
checks, wrap it in a workflow that reads `warning-count` and decides for
itself.
