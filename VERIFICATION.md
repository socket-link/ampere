# AMPERE CLI Verification Guide

## Quick Verification Steps

### 1. Verify Help Command Works
```bash
./ampere-cli/ampere help
```
**Expected**: Should show comprehensive help with keyboard controls including 'h', ':', 'ESC', etc.

### 2. Verify Default Command is 'start'
```bash
./ampere-cli/ampere --help
```
**Expected**: Should list `start` as the first command and say "(default)" in the Quick Start section

### 3. Test Interactive Dashboard
```bash
./ampere-cli/ampere
# or explicitly:
./ampere-cli/ampere start
```

**Expected Behavior**:
- Screen clears and enters full-screen mode (alternate buffer)
- Dashboard appears with system vitals
- Cursor is hidden
- Updates every second

**Keyboard Controls to Test**:
- Press `h` → Help overlay should appear with bordered box
- Press `h` again → Help should disappear
- Press `?` → Help should toggle
- Press `ESC` → Help should close (if open)
- Press `:` → Command prompt should appear at bottom: `:`
- Type `help` → Should show `:help` at bottom
- Press `ESC` → Should cancel command mode
- Press `q` or `Ctrl+C` → Should exit cleanly

### 4. Verify Terminal Rendering is Clean
After running `./ampere-cli/ampere` and then exiting:
- Terminal should be clear (no leftover text from previous command)
- Cursor should be visible again
- Should show: "AMPERE dashboard stopped"

## Common Issues

### Issue: "No changes visible"
**Solution**: Make sure you ran the build:
```bash
./gradlew :ampere-cli:clean :ampere-cli:installJvmDist --rerun-tasks
```

### Issue: "Java version error"
**Solution**: The wrapper script should handle this automatically. If not:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./ampere-cli/ampere
```

### Issue: "Can't see help overlay"
**Possible causes**:
1. Terminal too small - Help overlay needs at least 70 columns
2. Pressing wrong key - Use `h` or `?` (not `H`)
3. Help is displaying but being immediately overwritten - This would be a rendering bug

### Issue: "Command mode not working"
**Test**:
1. Press `:` (colon key)
2. Look at bottom of screen
3. Should see `:` prompt
4. Type letters - they should appear after the `:`
5. Press `ESC` to cancel

## What Changed

### New Commands
- `ampere help` - Comprehensive help (NEW)
- `ampere start` - Interactive dashboard (renamed from iwatch)
- `ampere` - Now defaults to `start` instead of `interactive`

### New Keyboard Controls (in dashboard)
- `h` or `?` - Toggle help overlay (NEW)
- `:` - Enter command mode (NEW)
- `ESC` - Close help / Cancel command (NEW)
- `q` - Quit (in addition to Ctrl+C) (NEW)

### Enhanced Features
- Alternate screen buffer (clean terminal experience)
- Hidden cursor during dashboard updates
- Help overlay with bordered box
- Command mode UI (framework ready, commands pending)

## Files to Check

If you want to verify the source code has your changes:

```bash
# Check StartCommand exists
ls -la ampere-cli/src/jvmMain/kotlin/link/socket/ampere/StartCommand.kt

# Check HelpCommand exists
ls -la ampere-cli/src/jvmMain/kotlin/link/socket/ampere/HelpCommand.kt

# Check HelpOverlayRenderer exists
ls -la ampere-cli/src/jvmMain/kotlin/link/socket/ampere/renderer/HelpOverlayRenderer.kt

# Verify classes are in JAR
unzip -l ampere-cli/build/install/ampere-cli-jvm/lib/ampere-cli-jvm.jar | grep -E "StartCommand|HelpCommand"
```

All should exist and show recent modification times.
