#!/bin/bash
#
# Demo Recording Script for Ampere Dashboard
#
# Records the live Ampere dashboard and converts to optimized GIFs
# for README and marketing materials.
#
# Usage:
#   ./scripts/record-demo.sh [options]
#
# Options:
#   --duration SECONDS   How long to record (default: 15)
#   --name NAME          Output file name (default: dashboard)
#
# Examples:
#   ./scripts/record-demo.sh                     # Record 15s dashboard demo
#   ./scripts/record-demo.sh --duration 30       # Record 30s
#   ./scripts/record-demo.sh --name my-demo      # Custom output name
#
# Required tools:
#   brew install asciinema
#   cargo install agg  # or: brew install agg
#   brew install gifsicle
#   brew install --cask font-jetbrains-mono

set -e

# Defaults
DURATION=45
DEMO_NAME="dashboard"
OUTPUT_DIR="assets/demos"

# Terminal dimensions
COLS=160
ROWS=40
FONT="Input Mono"
FONT_SIZE=14

# Parse options
while [[ $# -gt 0 ]]; do
    case "$1" in
        --duration)
            DURATION="$2"
            shift 2
            ;;
        --name)
            DEMO_NAME="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Check for required tools
check_tool() {
    if ! command -v "$1" &> /dev/null; then
        echo "Error: $1 is required but not installed."
        echo "Install with: $2"
        exit 1
    fi
}

check_tool asciinema "brew install asciinema"
check_tool agg "cargo install agg  # or brew install agg"
check_tool gifsicle "brew install gifsicle"

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Ensure Java 21+ is used (the CLI is compiled for JVM 21)
export JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 22 2>/dev/null || /usr/libexec/java_home -v 23 2>/dev/null || /usr/libexec/java_home -v 24 2>/dev/null)
if [ -z "$JAVA_HOME" ]; then
    echo "Error: Java 21+ is required but not found."
    echo "Install with: brew install --cask temurin@21"
    exit 1
fi

# Build the CLI distribution
echo "Building CLI distribution..."
./gradlew :ampere-cli:installJvmDist -q

# Create a temporary config that uses fast models for demo recording
DEMO_CONFIG=$(mktemp)
trap "rm -f '$DEMO_CONFIG'" EXIT
cat > "$DEMO_CONFIG" <<'YAML'
ai:
  provider: anthropic
  model: haiku-3
  backups:
    - provider: openai
      model: gpt-4o-mini
team:
  - role: product-manager
  - role: engineer
  - role: qa-tester
goal: "Add CI/CD pipeline"
YAML

# Set terminal size — both the window and the environment variables
# so the CLI process sees the correct dimensions
printf '\e[8;%d;%dt' "$ROWS" "$COLS"
export COLUMNS="$COLS"
export LINES="$ROWS"
stty cols "$COLS" rows "$ROWS" 2>/dev/null || true

AMPERE_BIN="ampere-cli/build/install/ampere-jvm/bin/ampere"

echo "=========================================="
echo "  Ampere Dashboard Recording"
echo "=========================================="
echo ""
echo "  Duration:  ${DURATION}s"
echo "  Terminal:  ${COLS}x${ROWS}"
echo "  Output:    $OUTPUT_DIR/$DEMO_NAME.cast"
echo "  Java:      $JAVA_HOME"
echo ""
echo "Recording will start in 3 seconds..."
sleep 3

# Record the dashboard for DURATION seconds, then send SIGINT to stop cleanly
asciinema rec "$OUTPUT_DIR/$DEMO_NAME.cast" \
    --cols "$COLS" \
    --rows "$ROWS" \
    --idle-time-limit 10 \
    --command "bash -c 'export COLUMNS=$COLS LINES=$ROWS; stty cols $COLS rows $ROWS 2>/dev/null; $AMPERE_BIN --config $DEMO_CONFIG run --goal \"Add CI/CD pipeline\" & PID=\$!; sleep $DURATION; kill \$PID 2>/dev/null; wait \$PID 2>/dev/null'"

echo ""
echo "Recording complete!"
echo ""

# Convert to GIF using agg
echo "Converting to GIF..."
agg "$OUTPUT_DIR/$DEMO_NAME.cast" "$OUTPUT_DIR/$DEMO_NAME.gif" \
    --font-family "$FONT" \
    --font-size "$FONT_SIZE" \
    --theme monokai \
    --fps-cap 15

# Optimize GIF with gifsicle
echo "Optimizing GIF..."
gifsicle -O3 --colors 128 "$OUTPUT_DIR/$DEMO_NAME.gif" -o "$OUTPUT_DIR/$DEMO_NAME-optimized.gif"

# Create thumbnail (smaller, lower quality for quick previews)
echo "Creating thumbnail..."
gifsicle -O3 --colors 64 --scale 0.5 "$OUTPUT_DIR/$DEMO_NAME.gif" -o "$OUTPUT_DIR/$DEMO_NAME-thumb.gif"

# Get file sizes
CAST_SIZE=$(du -h "$OUTPUT_DIR/$DEMO_NAME.cast" | cut -f1)
GIF_SIZE=$(du -h "$OUTPUT_DIR/$DEMO_NAME.gif" | cut -f1)
OPT_SIZE=$(du -h "$OUTPUT_DIR/$DEMO_NAME-optimized.gif" | cut -f1)
THUMB_SIZE=$(du -h "$OUTPUT_DIR/$DEMO_NAME-thumb.gif" | cut -f1)

echo ""
echo "=========================================="
echo "  Recording Complete!"
echo "=========================================="
echo ""
echo "  Files created:"
echo "    Cast:       $OUTPUT_DIR/$DEMO_NAME.cast ($CAST_SIZE)"
echo "    GIF:        $OUTPUT_DIR/$DEMO_NAME.gif ($GIF_SIZE)"
echo "    Optimized:  $OUTPUT_DIR/$DEMO_NAME-optimized.gif ($OPT_SIZE)"
echo "    Thumbnail:  $OUTPUT_DIR/$DEMO_NAME-thumb.gif ($THUMB_SIZE)"
echo ""
echo "  Next steps:"
echo "    1. Preview: open $OUTPUT_DIR/$DEMO_NAME-optimized.gif"
echo "    2. Share cast: asciinema upload $OUTPUT_DIR/$DEMO_NAME.cast"
echo "    3. Use in README with optimized GIF (<2MB recommended)"
echo ""
