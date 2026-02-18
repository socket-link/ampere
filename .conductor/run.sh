#!/usr/bin/env bash
set -euo pipefail

echo "=== JVM Unit Tests ==="
./gradlew jvmTest

echo "=== Build ==="
./gradlew assemble

echo "=== All checks passed ==="
