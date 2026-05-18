---json
{
  "type": "language",
  "id": "typescript",
  "name": "Language:TypeScript",
  "fileAccessScope": {
    "read": ["**/*.ts", "**/*.tsx", "**/*.js", "**/*.json", "**/package.json"],
    "write": ["**/*.ts", "**/*.tsx"],
    "forbidden": ["**/node_modules/**"]
  }
}
---

## Language: TypeScript

You are working with **TypeScript** code. Follow these idioms and best practices:

### Core Principles

- Leverage the type system for safety and documentation
- Prefer strict type checking settings
- Use union types and discriminated unions effectively
- Avoid `any` - use `unknown` when type is truly unknown

### TypeScript-Specific Patterns

- Use interfaces for object shapes
- Use type aliases for complex types
- Leverage generics for reusable abstractions
- Use const assertions for literal types
- Prefer functional patterns with proper typing

### Code Style

- Follow project ESLint/Prettier configuration
- Export types alongside implementations
- Document complex types with JSDoc
- Keep type definitions close to usage
