#!/bin/bash
# Jazz Test Demo - Post-Demo Validation Script
#
# This script validates that the demo ran successfully and
# produced the expected outputs.
#
# Usage: ./.ampere/demo/validate-demo-results.sh

set -e

echo "════════════════════════════════════════════════════════════════"
echo "Jazz Test Demo - Post-Demo Validation"
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

echo "1. Checking generated files..."
echo "──────────────────────────────────────────────────────────────"

OUTPUT_DIR="$HOME/.ampere/jazz-test-output"

if [ ! -d "$OUTPUT_DIR" ]; then
    check_fail "Output directory not found: $OUTPUT_DIR"
    echo ""
    echo "Demo may not have run successfully."
    exit 1
fi

# Look for Fibonacci.kt file
FIBONACCI_FILE=$(find "$OUTPUT_DIR" -name "Fibonacci.kt" -type f | head -1)

if [ -n "$FIBONACCI_FILE" ]; then
    check_pass "Fibonacci.kt file created"
    echo "         Location: $FIBONACCI_FILE"

    # Validate file contents
    if grep -q "fun fibonacci" "$FIBONACCI_FILE"; then
        check_pass "Contains fibonacci function"
    else
        check_fail "Missing fibonacci function"
    fi

    if grep -q "Int\|Long" "$FIBONACCI_FILE"; then
        check_pass "Uses appropriate types (Int/Long)"
    else
        check_warn "Type annotations not found"
    fi

    # Show file size
    FILE_SIZE=$(wc -c < "$FIBONACCI_FILE" | tr -d ' ')
    if [ "$FILE_SIZE" -gt 100 ]; then
        check_pass "File size: $FILE_SIZE bytes (reasonable)"
    else
        check_warn "File size: $FILE_SIZE bytes (seems small)"
    fi

    echo ""
    echo "File contents:"
    echo "───────────────────────────────────────────"
    cat "$FIBONACCI_FILE"
    echo "───────────────────────────────────────────"
else
    check_fail "Fibonacci.kt not found in $OUTPUT_DIR"
    echo "         Files present:"
    find "$OUTPUT_DIR" -type f | while read -r file; do
        echo "           - $(basename "$file")"
    done
fi

echo ""
echo "2. Checking database state..."
echo "──────────────────────────────────────────────────────────────"

DB_PATH="$HOME/.ampere/ampere.db"

if [ ! -f "$DB_PATH" ]; then
    check_warn "Database not found (demo may not have run)"
else
    check_pass "Database exists"

    if command -v sqlite3 &> /dev/null; then
        # Check tickets
        TICKET_COUNT=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM ticket;" 2>/dev/null || echo "0")
        if [ "$TICKET_COUNT" -gt 0 ]; then
            check_pass "Tickets created: $TICKET_COUNT"

            # Get the last ticket
            LAST_TICKET=$(sqlite3 "$DB_PATH" "SELECT title FROM ticket ORDER BY created_at DESC LIMIT 1;" 2>/dev/null || echo "")
            echo "         Last ticket: $LAST_TICKET"
        else
            check_fail "No tickets found in database"
        fi

        # Check knowledge
        KNOWLEDGE_COUNT=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM knowledge;" 2>/dev/null || echo "0")
        if [ "$KNOWLEDGE_COUNT" -gt 0 ]; then
            check_pass "Knowledge entries stored: $KNOWLEDGE_COUNT"
        else
            check_warn "No knowledge entries found"
        fi

        # Check events
        EVENT_COUNT=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM event;" 2>/dev/null || echo "0")
        if [ "$EVENT_COUNT" -gt 0 ]; then
            check_pass "Events logged: $EVENT_COUNT"
        else
            check_warn "No events found"
        fi
    else
        check_warn "sqlite3 not available - skipping database checks"
    fi
fi

echo ""
echo "3. Checking for common issues..."
echo "──────────────────────────────────────────────────────────────"

# Check if there are multiple files (might indicate agent created extras)
FILE_COUNT=$(find "$OUTPUT_DIR" -type f 2>/dev/null | wc -l | tr -d ' ')

if [ "$FILE_COUNT" -eq 1 ]; then
    check_pass "Only 1 file created (as expected)"
elif [ "$FILE_COUNT" -gt 1 ]; then
    check_warn "Multiple files created ($FILE_COUNT total)"
    echo "         Agent may have created additional files:"
    find "$OUTPUT_DIR" -type f | while read -r file; do
        echo "           - $(basename "$file")"
    done
else
    check_fail "No files created"
fi

# Check for error logs (if they exist)
if [ -d "$HOME/.ampere/logs" ]; then
    ERROR_LOGS=$(find "$HOME/.ampere/logs" -name "*.log" -type f -mmin -10 2>/dev/null || true)
    if [ -n "$ERROR_LOGS" ]; then
        check_warn "Recent log files found (check for errors)"
        echo "$ERROR_LOGS" | while read -r log; do
            echo "         - $log"
        done
    fi
fi

echo ""
echo "4. Performance metrics..."
echo "──────────────────────────────────────────────────────────────"

# Try to extract timing information from database
if [ -f "$DB_PATH" ] && command -v sqlite3 &> /dev/null; then
    # Get most recent ticket
    TICKET_CREATED=$(sqlite3 "$DB_PATH" "SELECT created_at FROM ticket ORDER BY created_at DESC LIMIT 1;" 2>/dev/null || echo "")
    TICKET_UPDATED=$(sqlite3 "$DB_PATH" "SELECT updated_at FROM ticket ORDER BY updated_at DESC LIMIT 1;" 2>/dev/null || echo "")

    if [ -n "$TICKET_CREATED" ] && [ -n "$TICKET_UPDATED" ]; then
        echo "         Ticket created: $TICKET_CREATED"
        echo "         Ticket updated: $TICKET_UPDATED"
        # Note: Time calculation would require date parsing
    fi

    # Count events by type to see cognitive cycle
    echo ""
    echo "         Event breakdown:"
    sqlite3 "$DB_PATH" "SELECT type, COUNT(*) as count FROM event GROUP BY type ORDER BY count DESC LIMIT 10;" 2>/dev/null | while read -r line; do
        echo "           $line"
    done || echo "           Could not retrieve event stats"
fi

echo ""
echo "════════════════════════════════════════════════════════════════"
echo "Validation Summary"
echo "════════════════════════════════════════════════════════════════"
echo ""

if [ $ERRORS -eq 0 ] && [ $WARNINGS -eq 0 ]; then
    echo -e "${GREEN}✓ Demo completed successfully!${NC}"
    echo ""
    echo "The Fibonacci.kt file was generated correctly and all validations passed."
    echo ""
    echo "Next steps:"
    echo "  1. Review the generated file above"
    echo "  2. Validate that input was responsive during execution"
    echo "  3. Confirm all phase outputs were visible in the TUI"
    echo ""
    exit 0
elif [ $ERRORS -eq 0 ]; then
    echo -e "${YELLOW}⚠ Demo completed with $WARNINGS warning(s)${NC}"
    echo ""
    echo "Review warnings above. Demo likely succeeded but with minor issues."
    exit 0
else
    echo -e "${RED}✗ Demo failed validation: $ERRORS error(s), $WARNINGS warning(s)${NC}"
    echo ""
    echo "Please review errors above and check:"
    echo "  - Did the agent complete the EXECUTE phase?"
    echo "  - Were there any error messages in the TUI?"
    echo "  - Did the demo timeout or crash?"
    exit 1
fi
