#!/bin/bash
#
# Demo Recording Script for Ampere TUI
#
# Records terminal demos and converts them to optimized GIFs for README
# and marketing materials.
#
# Usage:
#   ./scripts/record-demo.sh [scenario-name] [options]
#
# Examples:
#   ./scripts/record-demo.sh                    # Record default scenario
#   ./scripts/record-demo.sh release-notes      # Record specific scenario
#   ./scripts/record-demo.sh code-review --fast # Quick recording with 2x speed
#
# Required tools:
#   brew install asciinema
#   cargo install agg  # or: brew install agg
#   brew install gifsicle
#   brew install --cask font-jetbrains-mono

set -e

# Configuration
DEMO_NAME=${1:-release-notes}
SPEED=${2:-1.0}
OUTPUT_DIR="assets/demos"

# Terminal dimensions (match DemoConfig defaults)
COLS=100
ROWS=30
FONT="JetBrains Mono"
FONT_SIZE=14

# Parse options
EXTRA_ARGS=""
if [[ "$2" == "--fast" ]]; then
    SPEED="2.0"
    EXTRA_ARGS="--speed 2.0"
elif [[ "$2" == "--slow" ]]; then
    SPEED="0.5"
    EXTRA_ARGS="--speed 0.5"
fi

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

# Set terminal size
printf '\e[8;%d;%dt' "$ROWS" "$COLS"

echo "=========================================="
echo "  Ampere Demo Recording"
echo "=========================================="
echo ""
echo "  Scenario:  $DEMO_NAME"
echo "  Speed:     ${SPEED}x"
echo "  Terminal:  ${COLS}x${ROWS}"
echo "  Output:    $OUTPUT_DIR/$DEMO_NAME.cast"
echo ""
echo "Recording will start in 3 seconds..."
sleep 3

# Record the demo using asciinema
asciinema rec "$OUTPUT_DIR/$DEMO_NAME.cast" \
    --cols "$COLS" \
    --rows "$ROWS" \
    --idle-time-limit 2 \
    --command "./gradlew :ampere-cli:run --args='demo --scripted --scenario $DEMO_NAME $EXTRA_ARGS' -q"

echo ""
echo "Recording complete!"
echo ""

# Convert to GIF using agg
echo "Converting to GIF..."
agg "$OUTPUT_DIR/$DEMO_NAME.cast" "$OUTPUT_DIR/$DEMO_NAME.gif" \
    --font-family "$FONT" \
    --font-size "$FONT_SIZE" \
    --theme monokai \
    --fps 15

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
