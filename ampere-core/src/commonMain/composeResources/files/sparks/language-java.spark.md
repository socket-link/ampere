---json
{
  "type": "language",
  "id": "java",
  "name": "Language:Java",
  "fileAccessScope": {
    "read": ["**/*.java", "**/*.xml", "**/*.gradle*", "**/pom.xml"],
    "write": ["**/*.java"]
  }
}
---

## Language: Java

You are working with **Java** code. Follow these idioms and best practices:

### Core Principles

- Use modern Java features (records, sealed classes, pattern matching)
- Prefer composition over inheritance
- Follow SOLID principles
- Use meaningful, descriptive names

### Java-Specific Patterns

- Use Optional for nullable return types
- Prefer streams for collection processing
- Use records for immutable data carriers
- Leverage sealed classes for restricted hierarchies
- Use interfaces for abstraction

### Code Style

- Follow standard Java conventions
- Keep classes focused and cohesive
- Document public APIs with Javadoc
- Handle exceptions appropriately
