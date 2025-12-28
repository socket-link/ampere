# Jazz Test - Quick Start

## 🚀 Run the Jazz Test in 3 Steps

### Step 1: Build the CLI
```bash
./gradlew :ampere-cli:installJvmDist
```

### Step 2: Run the Jazz Test
```bash
./ampere-cli/ampere jazz-test
```

### Step 3: Watch it work! 🎉

The agent will autonomously:
- ✅ Perceive the ticket assignment
- ✅ Plan the implementation
- ✅ Execute code writing
- ✅ Learn from the outcome

Output will be saved to: `~/.ampere/jazz-test-output/Fibonacci.kt`

---

## 📊 Observe in Real-Time (Optional)

Run these in **two separate terminals**:

**Terminal 1** - Dashboard:
```bash
./ampere-cli/ampere start
```

**Terminal 2** - Jazz Test:
```bash
./ampere-cli/ampere jazz-test
```

In the dashboard:
- Press `d` for Dashboard mode
- Press `e` for Event stream
- Press `m` for Memory operations
- Press `1` for Agent focus view

---

## ⚠️ Prerequisites

You need an Anthropic API key in `local.properties`:
```properties
anthropic.api.key=sk-ant-your-key-here
```

---

## 📖 Full Documentation

See [JAZZ_TEST_GUIDE.md](JAZZ_TEST_GUIDE.md) for:
- Detailed explanation of the cognitive cycle
- Debugging tips
- Architecture overview
- Troubleshooting guide

---

## ✅ Success Criteria

The test passes when you see:

```
═══════════════════════════════════════════════════════════
✅ SUCCESS! Agent completed the task in X seconds
═══════════════════════════════════════════════════════════

📄 Generated file: /Users/you/.ampere/jazz-test-output/Fibonacci.kt

✅ Code validation passed
   ✓ Contains fibonacci function
   ✓ Uses appropriate types
```

**You've just witnessed autonomous agency in action!** 🎭
