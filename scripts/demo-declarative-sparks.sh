#!/usr/bin/env bash
#
# demo-declarative-sparks.sh
#
# Smoke-demo for AMPR-161 (declarative PhaseSpark authoring surface).
#
# Runs the verbose DeclarativeSparkDemo JVM test, which exercises:
#   - bundled .spark.md loading via DefaultPhaseSparkLibrary
#   - PhaseSparkLibrary.selectFor matching for a sample task description
#   - PhaseSparkManager behavior with AmpereSpikeFlags.declarativeSparksEnabled OFF vs ON
#   - the AgentLLMService.activePromptProvider seam delivering declarative spark
#     bodies into the LLM payload
#
# The demo test writes a human-readable report to
# ampere-core/build/declarative-sparks-demo.txt; this script prints that report.
#
# Usage:
#   scripts/demo-declarative-sparks.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPORT="$REPO_ROOT/ampere-core/build/declarative-sparks-demo.txt"

cd "$REPO_ROOT"

echo "==> Running DeclarativeSparkDemo (this triggers JVM test execution)..."
./gradlew :ampere-core:jvmTest \
    --tests "link.socket.ampere.agents.domain.cognition.sparks.DeclarativeSparkDemo" \
    --rerun-tasks \
    --console=plain

if [ ! -f "$REPORT" ]; then
    echo "ERROR: expected report at $REPORT but it was not produced." >&2
    exit 1
fi

echo
echo "==> Demo report:"
echo
cat "$REPORT"
