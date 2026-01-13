#!/bin/bash

echo "Testing database fixes..."
echo ""

# Check current database size
echo "Current database size:"
ls -lh ~/.ampere/ampere.db 2>/dev/null || echo "No database found"
echo ""

# Try to run Ampere status command (should not get SQLITE_BUSY)
echo "Testing database connection (checking for SQLITE_BUSY errors)..."
./ampere-cli/ampere status 2>&1 &
PID=$!

# Wait a bit for startup
sleep 5

# Kill the process
kill -9 $PID 2>/dev/null || true
wait $PID 2>/dev/null || true

echo ""
echo "Check complete. If you saw 'SQLITE_BUSY' above, the fix didn't work."
echo "If you saw 'Starting database maintenance...', the fix is working!"
