#!/usr/bin/env bash
set -euo pipefail

echo "=== Ktlint Check ==="
./gradlew ktlintCheck

echo "=== JVM Unit Tests ==="
./gradlew jvmTest

echo "=== Build ==="
./gradlew build -x test -x ktlintCheck

echo "=== All checks passed ==="
