#!/bin/bash
# Jazz Test Demo - Pre-flight Validation Script
#
# This script validates that the environment is properly configured
# for running the Jazz Test demo scenario.
#
# Usage: ./.ampere/demo/validate-demo-setup.sh

set -e

echo "════════════════════════════════════════════════════════════════"
echo "Jazz Test Demo - Pre-flight Validation"
echo "════════════════════════════════════════════════════════════════"
echo ""

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

ERRORS=0
WARNINGS=0

# Helper functions
check_pass() {
    echo -e "${GREEN}✓${NC} $1"
}

check_fail() {
    echo -e "${RED}✗${NC} $1"
    ((ERRORS++))
}

check_warn() {
    echo -e "${YELLOW}⚠${NC} $1"
    ((WARNINGS++))
}

echo "1. Checking build..."
echo "──────────────────────────────────────────────────────────────"

# Check if ampere-cli is built
if [ -f "./ampere-cli/build/install/ampere-cli-jvm/bin/ampere-cli" ]; then
    check_pass "ampere-cli executable exists"
else
    check_fail "ampere-cli executable not found"
    echo "         Run: ./gradlew :ampere-cli:installJvmDist"
fi

# Check if wrapper script exists
if [ -f "./ampere-cli/ampere" ]; then
    check_pass "ampere wrapper script exists"
else
    check_warn "ampere wrapper script not found (optional)"
fi

echo ""
echo "2. Checking dispatcher fixes (Issue #182)..."
echo "──────────────────────────────────────────────────────────────"

# Check if CodeAgent has Dispatchers.IO fixes
if grep -q "Dispatchers.IO" ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/definition/CodeAgent.kt; then
    count=$(grep -c "Dispatchers.IO" ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/definition/CodeAgent.kt || true)
    if [ "$count" -ge 7 ]; then
        check_pass "CodeAgent dispatcher fixes applied (found $count instances)"
    else
        check_warn "CodeAgent dispatcher fixes incomplete (found $count/7 instances)"
    fi
else
    check_fail "CodeAgent dispatcher fixes NOT applied"
fi

# Check if AgentLLMService has withContext fix
if grep -q "withContext(Dispatchers.IO)" ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/reasoning/AgentLLMService.kt; then
    check_pass "AgentLLMService network call dispatching applied"
else
    check_fail "AgentLLMService network call dispatching NOT applied"
fi

# Check if JazzTestRunner has file I/O fix
if grep -q "withContext(Dispatchers.IO)" ampere-cli/src/jvmMain/kotlin/link/socket/ampere/demo/JazzTestRunner.kt; then
    check_pass "JazzTestRunner file I/O dispatching applied"
else
    check_fail "JazzTestRunner file I/O dispatching NOT applied"
fi

echo ""
echo "3. Checking output visibility fixes (Issue #183)..."
echo "──────────────────────────────────────────────────────────────"

# Check if GoalHandler has output tracking
if grep -q "progressPane.setPerceiveResult" ampere-cli/src/jvmMain/kotlin/link/socket/ampere/cli/goal/GoalHandler.kt; then
    check_pass "GoalHandler PERCEIVE output tracking present"
else
    check_warn "GoalHandler PERCEIVE output tracking not found"
fi

if grep -q "progressPane.setPlanResult" ampere-cli/src/jvmMain/kotlin/link/socket/ampere/cli/goal/GoalHandler.kt; then
    check_pass "GoalHandler PLAN output tracking present"
else
    check_warn "GoalHandler PLAN output tracking not found"
fi

if grep -q "progressPane.addFileWritten" ampere-cli/src/jvmMain/kotlin/link/socket/ampere/cli/goal/GoalHandler.kt; then
    check_pass "GoalHandler file write tracking present"
else
    check_warn "GoalHandler file write tracking not found"
fi

if grep -q "progressPane.addKnowledgeStored" ampere-cli/src/jvmMain/kotlin/link/socket/ampere/cli/goal/GoalHandler.kt; then
    check_pass "GoalHandler knowledge tracking present"
else
    check_warn "GoalHandler knowledge tracking not found"
fi

echo ""
echo "4. Checking terminal environment..."
echo "──────────────────────────────────────────────────────────────"

# Check terminal size
COLS=$(tput cols)
ROWS=$(tput lines)

if [ "$COLS" -ge 120 ] && [ "$ROWS" -ge 40 ]; then
    check_pass "Terminal size: ${COLS}x${ROWS} (minimum 120x40)"
else
    check_warn "Terminal size: ${COLS}x${ROWS} (recommended minimum: 120x40)"
    echo "         Resize terminal or run: printf '\\e[8;50;140t'"
fi

# Check if asciinema is installed (for recording)
if command -v asciinema &> /dev/null; then
    check_pass "asciinema installed (for recording)"
else
    check_warn "asciinema not installed (optional, for recording)"
    echo "         Install: brew install asciinema (macOS)"
fi

echo ""
echo "5. Checking API configuration..."
echo "──────────────────────────────────────────────────────────────"

# Check if local.properties exists
if [ -f "local.properties" ]; then
    check_pass "local.properties file exists"

    # Check for Anthropic API key
    if grep -q "anthropic.api.key" local.properties; then
        # Don't print the actual key, just check if it's not empty
        if grep "anthropic.api.key" local.properties | grep -q "sk-ant"; then
            check_pass "Anthropic API key configured"
        else
            check_fail "Anthropic API key appears invalid or empty"
        fi
    else
        check_fail "Anthropic API key not configured"
        echo "         Add to local.properties: anthropic.api.key=sk-ant-..."
    fi
else
    check_fail "local.properties file not found"
    echo "         Create file with: anthropic.api.key=sk-ant-..."
fi

echo ""
echo "6. Checking database state..."
echo "──────────────────────────────────────────────────────────────"

DB_PATH="$HOME/.ampere/ampere.db"

if [ -f "$DB_PATH" ]; then
    check_warn "Database exists at $DB_PATH"
    echo "         For fresh demo, delete with: rm $DB_PATH"

    # Check if sqlite3 is available
    if command -v sqlite3 &> /dev/null; then
        TICKET_COUNT=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM ticket;" 2>/dev/null || echo "0")
        echo "         Current tickets: $TICKET_COUNT"
    fi
else
    check_pass "No existing database (fresh state)"
fi

# Check output directory
OUTPUT_DIR="$HOME/.ampere/jazz-test-output"
if [ -d "$OUTPUT_DIR" ]; then
    FILE_COUNT=$(find "$OUTPUT_DIR" -type f 2>/dev/null | wc -l | tr -d ' ')
    if [ "$FILE_COUNT" -gt 0 ]; then
        check_warn "Output directory contains $FILE_COUNT file(s)"
        echo "         For clean demo, clear with: rm -rf $OUTPUT_DIR/*"
    else
        check_pass "Output directory exists and is empty"
    fi
else
    check_pass "Output directory doesn't exist (will be created)"
fi

echo ""
echo "════════════════════════════════════════════════════════════════"
echo "Validation Summary"
echo "════════════════════════════════════════════════════════════════"
echo ""

if [ $ERRORS -eq 0 ] && [ $WARNINGS -eq 0 ]; then
    echo -e "${GREEN}✓ All checks passed!${NC}"
    echo ""
    echo "You're ready to run the demo:"
    echo "  ./ampere-cli/ampere test jazz"
    echo ""
    echo "Or record it:"
    echo "  asciinema rec jazz-demo.cast --cols 140 --rows 50"
    echo "  ./ampere-cli/ampere test jazz"
    exit 0
elif [ $ERRORS -eq 0 ]; then
    echo -e "${YELLOW}⚠ $WARNINGS warning(s) - demo may work with limitations${NC}"
    echo ""
    echo "Review warnings above and proceed if acceptable."
    exit 0
else
    echo -e "${RED}✗ $ERRORS error(s), $WARNINGS warning(s)${NC}"
    echo ""
    echo "Please fix errors above before running the demo."
    exit 1
fi
