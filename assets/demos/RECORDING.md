# Demo Recording Guide

This guide documents how to record high-quality demo GIFs for Ampere's README and marketing materials.

## Prerequisites

Install the required tools:

```bash
# Recording tool
brew install asciinema

# GIF converter (either option works)
cargo install agg
# or: brew install agg

# GIF optimizer
brew install gifsicle

# Monospace font with excellent Unicode support
brew install --cask font-jetbrains-mono
```

## Quick Start

Record the default demo:

```bash
./scripts/record-demo.sh
```

Record a specific scenario:

```bash
./scripts/record-demo.sh release-notes
./scripts/record-demo.sh code-review
```

Speed options:

```bash
./scripts/record-demo.sh release-notes --fast  # 2x speed
./scripts/record-demo.sh release-notes --slow  # 0.5x speed
```

## Terminal Setup

For best results, configure your terminal to match the Ampere demo profile:

| Setting | Value |
|---------|-------|
| Font | JetBrains Mono |
| Font Size | 14pt |
| Columns | 100 |
| Rows | 30 |
| Background | #1a1a2e |
| Foreground | #eaeaea |

See `terminal-profile.json` for the full color scheme.

### iTerm2 Setup

1. Open iTerm2 Preferences → Profiles
2. Create a new profile named "Ampere Demo"
3. Set:
   - Text → Font: JetBrains Mono 14pt
   - Window → Columns: 100, Rows: 30
   - Colors → Import from `terminal-profile.json` or set manually

### Terminal.app Setup

1. Open Terminal Preferences → Profiles
2. Create a new profile
3. Set font to JetBrains Mono 14pt
4. Set window size to 100x30
5. Configure colors to match the profile

## Output Files

After recording, you'll find these files in `assets/demos/`:

| File | Description | Typical Size |
|------|-------------|--------------|
| `{scenario}.cast` | Asciinema recording | 50-200KB |
| `{scenario}.gif` | Full-quality GIF | 2-8MB |
| `{scenario}-optimized.gif` | Optimized GIF (128 colors) | 1-3MB |
| `{scenario}-thumb.gif` | Thumbnail (50% scale, 64 colors) | 200-800KB |

## Available Scenarios

| Scenario | Description | Duration |
|----------|-------------|----------|
| `release-notes` | Two agents collaborate on release notes | ~19s |
| `code-review` | Three agents perform code review | ~21s |

## Best Practices

### For README GIFs

1. Use the optimized GIF (`*-optimized.gif`)
2. Keep file size under 2MB for fast loading
3. Consider the thumbnail for mobile/preview

### For Asciinema Sharing

Upload the cast file to asciinema.org:

```bash
asciinema upload assets/demos/release-notes.cast
```

Then embed in documentation:

```markdown
[![Demo](https://asciinema.org/a/XXXXXX.svg)](https://asciinema.org/a/XXXXXX)
```

### For Quality

- Record at 1x speed for best quality
- Use `--fast` only for quick tests
- Ensure terminal window is exactly 100x30
- Close other applications to reduce load

## Troubleshooting

### GIF is too large

Try reducing colors further:

```bash
gifsicle -O3 --colors 64 input.gif -o output.gif
```

Or reduce scale:

```bash
gifsicle --scale 0.75 input.gif -o output.gif
```

### Unicode glyphs not rendering

1. Ensure JetBrains Mono is installed
2. Check terminal Unicode support
3. Set `LC_ALL=en_US.UTF-8` in your shell

### Colors look wrong

1. Verify terminal supports 256 colors: `echo $TERM`
2. Should be `xterm-256color` or similar
3. Try: `export TERM=xterm-256color`

## File Structure

```
assets/
├── demos/
│   ├── RECORDING.md           # This file
│   ├── terminal-profile.json  # Terminal color scheme
│   ├── release-notes.cast     # Asciinema recording
│   ├── release-notes.gif      # Full quality GIF
│   ├── release-notes-optimized.gif  # README-ready GIF
│   └── release-notes-thumb.gif      # Thumbnail
└── logo/
    └── (future: logo files)
```
