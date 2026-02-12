#!/usr/bin/env bash
set -euo pipefail

echo "=== JVM Unit Tests ==="
./gradlew jvmTest

echo "=== Build ==="
./gradlew build -x test

echo "=== All checks passed ==="
