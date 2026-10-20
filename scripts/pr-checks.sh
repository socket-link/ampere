#!/usr/bin/env bash
# Wait on a PR's CI and report only what needs attention.
#
# Built for agents. `gh pr checks --watch` reprints its whole table every interval and cannot
# outlast the Bash tool's 10-minute cap, so this polls quietly inside a time budget and prints one
# short report. Run it after every push and act on the exit code (AGENTS.md, "Ticket Delivery Loop").
#
# Usage: scripts/pr-checks.sh [--budget <seconds>] [--fail-on required|any] [<pr number | url | branch>]
#
#   --budget    seconds to wait before giving up with exit 8 (default 540, under the 10-minute cap)
#   --fail-on   which red check ends the wait early: a `required` one, or `any` (default: any)
#   <pr>        defaults to the PR for the current branch
#
# Exit codes:
#   0  every required check passed or skipped, and every other check has finished
#   1  a check that --fail-on covers failed, was cancelled, or can no longer run; reported as soon
#      as it happens
#   2  usage error, gh error, or no open PR
#   3  the PR conflicts with its base branch, so CI cannot run until you rebase
#   8  still pending when the budget ran out; run it again
#
# Required checks are read from the base branch's protection rule, so a check added to the rule
# is waited on without editing this script. With no rule to read, every check counts as required.

set -uo pipefail

budget=540
interval=30
# The required checks here are aggregator jobs that report only after every job they aggregate
# has finished, and their logs only name the job that failed. So the wait ends on the first red
# of any kind, which is the job worth reading.
fail_on=any
# How long to wait for a fresh push to become the PR head before reading the old head's checks.
push_grace=90
target=""

usage() {
  awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --budget)
      [ $# -ge 2 ] || { usage >&2; exit 2; }
      budget="$2"
      shift 2
      ;;
    --fail-on)
      [ $# -ge 2 ] || { usage >&2; exit 2; }
      fail_on="$2"
      shift 2
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    -*)
      usage >&2
      exit 2
      ;;
    *)
      target="$1"
      shift
      ;;
  esac
done

case "$budget" in
  '' | *[!0-9]*)
    echo "--budget takes whole seconds, got '$budget'" >&2
    exit 2
    ;;
esac

case "$fail_on" in
  required | any) ;;
  *)
    echo "--fail-on takes required or any, got '$fail_on'" >&2
    exit 2
    ;;
esac

for tool in gh jq git; do
  command -v "$tool" >/dev/null || { echo "$tool is not installed" >&2; exit 2; }
done

if ! pr_json=$(gh pr view ${target:+"$target"} --json number,url,state,baseRefName 2>&1); then
  echo "No PR found${target:+ for '$target'}: $pr_json" >&2
  echo "Open one first: gh pr create --base main" >&2
  exit 2
fi

number=$(jq -r .number <<<"$pr_json")
url=$(jq -r .url <<<"$pr_json")
state=$(jq -r .state <<<"$pr_json")
base=$(jq -r .baseRefName <<<"$pr_json")

if [ "$state" != OPEN ]; then
  echo "PR #$number is $state: $url"
  [ "$state" = MERGED ] && exit 0
  exit 2
fi

# With no explicit target the PR is this branch's, so its head should become the local HEAD.
local_sha=""
[ -z "$target" ] && local_sha=$(git rev-parse HEAD 2>/dev/null || true)

# With no rule to read (unprotected, or no access), fall back to whichever registered checks
# GitHub marks required, and failing that to every registered check. Test the exit status, not the
# output: on a 404 `gh api --jq` still prints the error body to stdout.
required_from_rule=0
if required=$(gh api "repos/{owner}/{repo}/branches/$base/protection/required_status_checks" \
  --jq '.contexts[]' 2>/dev/null) && [ -n "$required" ]; then
  required_from_rule=1
fi

started=$(date +%s)
deadline=$((started + budget))
head_sha=""
report=""
never_ran=0

while :; do
  now=$(date +%s)
  view=$(gh pr view "$number" --json mergeable,headRefOid 2>/dev/null || echo '{}')
  mergeable=$(jq -r '.mergeable // "UNKNOWN"' <<<"$view")
  head_sha=$(jq -r '.headRefOid // ""' <<<"$view")

  if [ "$mergeable" = CONFLICTING ]; then
    echo "PR #$number @ ${head_sha:0:9}: $url"
    echo "CONFLICT  PR #$number conflicts with $base, so CI will not run on this head."
    echo "Rebase onto origin/$base, re-run your local validation, then push with --force-with-lease."
    exit 3
  fi

  # Right after a push GitHub can still report the previous head, whose checks are all finished.
  if [ -n "$local_sha" ] && [ "$head_sha" != "$local_sha" ] \
    && [ "$now" -lt $((started + push_grace)) ] && [ "$now" -lt "$deadline" ]; then
    sleep 10
    continue
  fi

  checks=$(gh pr checks "$number" --json name,bucket,link 2>/dev/null || true)
  jq -e 'type == "array"' >/dev/null 2>&1 <<<"$checks" || checks='[]'

  if [ "$required_from_rule" = 0 ]; then
    required=$(gh pr checks "$number" --required --json name --jq '.[].name' 2>/dev/null) || required=""
    [ -n "$required" ] || required=$(jq -r '.[].name' <<<"$checks")
  fi
  required_json=$(printf '%s\n' "$required" | jq -R . | jq -s 'map(select(length > 0))')

  report=$(jq -r --argjson req "$required_json" '
    def kind: .name as $n | if ($req | any(. == $n)) then "required" else "not required" end;
    def job: (.link // "") | (capture("/runs/(?<run>[0-9]+)/job/(?<job>[0-9]+)") // {})
      | if .job then "run \(.run) job \(.job)" else "" end;
    (map(.name)) as $seen
    | if length == 0 and ($req | length) == 0 then ["PENDING\tno checks registered for this head yet\t"]
      else
        map(select(.bucket == "fail" or .bucket == "cancel")
          | "\(if .bucket == "fail" then "FAIL" else "CANCEL" end)\t\(.name) (\(kind))\t\(job)")
        + map(select(.bucket == "pending") | "PENDING\t\(.name) (\(kind))\t")
        + ($req - $seen | map("PENDING\t\(.) (required)\tnot registered yet"))
      end
    | .[]' <<<"$checks")

  required_failed=$(grep -cE '^(FAIL|CANCEL).*\(required\)' <<<"$report" || true)
  any_failed=$(grep -cE '^(FAIL|CANCEL)' <<<"$report" || true)
  blocking_failed=$required_failed
  [ "$fail_on" = any ] && blocking_failed=$any_failed
  anything_pending=$(grep -c '^PENDING' <<<"$report" || true)
  unregistered=$(grep -c 'not registered yet$' <<<"$report" || true)
  now=$(date +%s)

  if [ "$blocking_failed" -gt 0 ] || [ "$anything_pending" -eq 0 ] || [ "$now" -ge "$deadline" ]; then
    break
  fi

  # A required check that has not registered once every run for this head has finished, one of
  # them unsuccessfully, never will: a gate job upstream of it failed or never got a runner. The
  # failed run is the tell; "all finished" alone can be a fast workflow beating a slow one's start.
  if [ "$unregistered" -gt 0 ] && [ "$unregistered" -eq "$anything_pending" ] && [ -n "$head_sha" ]; then
    runs=$(gh run list --commit "$head_sha" --json status,conclusion --jq '
      if length == 0 then "none"
      elif any(.[]; .status != "completed") then "open"
      elif any(.[]; .conclusion != "success" and .conclusion != "skipped" and .conclusion != "neutral") then "dead"
      else "clean" end' 2>/dev/null || echo none)
    if [ "$runs" = dead ]; then
      never_ran=1
      break
    fi
  fi

  remaining=$((deadline - now))
  sleep $((remaining < interval ? remaining : interval))
done

waited=$(($(date +%s) - started))
echo "PR #$number @ ${head_sha:0:9}: $url"
if [ -n "$local_sha" ] && [ -n "$head_sha" ] && [ "$head_sha" != "$local_sha" ]; then
  echo "note: local HEAD ${local_sha:0:9} is not the PR head, so these results are for ${head_sha:0:9}. Push first?"
fi
[ -n "$report" ] && printf '%s\n' "$report" | column -t -s "$(printf '\t')"

# A job that "failed" with zero steps never got a runner: an Actions billing block or a runner
# outage, which no change to the diff can fix (AGENTS.md, "Reading a red check").
for job_id in $(sed -nE 's/^(FAIL|CANCEL).*job ([0-9]+)$/\2/p' <<<"$report"); do
  steps=$(gh api "repos/{owner}/{repo}/actions/jobs/$job_id" --jq '.steps | length' 2>/dev/null || true)
  if [ "$steps" = 0 ]; then
    echo "note: job $job_id never started (0 steps). That is Actions billing or runner allocation, not the diff."
  fi
done

if [ "$blocking_failed" -gt 0 ]; then
  # Point at the job that actually failed. Under --fail-on any, a red check that is not required is
  # usually what a required aggregator is about to report, and the aggregator's own log only names it.
  first_job=""
  [ "$fail_on" = any ] \
    && first_job=$(grep -E '^(FAIL|CANCEL).*\(not required\)' <<<"$report" | sed -nE 's/.*job ([0-9]+)$/\1/p' | head -1)
  [ -n "$first_job" ] \
    || first_job=$(grep -E '^(FAIL|CANCEL)' <<<"$report" | sed -nE 's/.*job ([0-9]+)$/\1/p' | head -1)
  if [ "$required_failed" -gt 0 ]; then
    echo "verdict: a required check is red after ${waited}s."
  else
    echo "verdict: a check is red after ${waited}s, and --fail-on is any."
  fi
  # `gh run view --log-failed` refuses until the whole run finishes; the jobs API serves one
  # finished job's log while the rest of the run is still going.
  if [ -n "$first_job" ]; then
    echo "Read it: gh api repos/{owner}/{repo}/actions/jobs/$first_job/logs --allow-escape-sequences | grep -nE 'FAILED|What went wrong| e: |##\[error\]'"
  fi
  exit 1
fi

if [ "$never_ran" = 1 ]; then
  echo "verdict: every run for this head has finished, but the required checks marked 'not registered yet' never ran. Read the red jobs above."
  exit 1
fi

if [ "$anything_pending" -gt 0 ]; then
  echo "verdict: still running after ${waited}s. Run scripts/pr-checks.sh again."
  exit 8
fi

required_count=$(jq 'length' <<<"$required_json")
if [ "$any_failed" -gt 0 ]; then
  echo "verdict: all $required_count required checks passed; $any_failed check(s) that are not required are red, listed above."
else
  echo "verdict: all $required_count required checks passed, and nothing else is red."
fi
exit 0
