---json
{
  "type": "language",
  "id": "python",
  "name": "Language:Python",
  "fileAccessScope": {
    "read": ["**/*.py", "**/requirements*.txt", "**/pyproject.toml", "**/setup.py"],
    "write": ["**/*.py"],
    "forbidden": ["**/__pycache__/**", "**/*.pyc", "**/venv/**", "**/.venv/**"]
  }
}
---

## Language: Python

You are working with **Python** code. Follow these idioms and best practices:

### Core Principles

- Follow PEP 8 style guide
- Use type hints for better tooling and documentation
- Prefer explicit over implicit
- Keep it simple and readable

### Python-Specific Patterns

- Use dataclasses or Pydantic for data structures
- Leverage list/dict comprehensions appropriately
- Use context managers for resource management
- Prefer generators for large sequences
- Use descriptive variable names

### Code Style

- Follow PEP 8 naming conventions
- Document with docstrings
- Keep functions focused
- Handle exceptions explicitly
