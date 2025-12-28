# Ampere Issue Definitions

This directory contains JSON files that define batches of GitHub issues to be created using the `ampere issues create` command.

## Quick Start

```bash
# Create issues from a file
./ampere-cli/ampere issues create -f .ampere/issues/pm-agent-epic.json

# Validate without creating
./ampere-cli/ampere issues create -f .ampere/issues/pm-agent-epic.json --dry-run
```

## JSON Format

Issues are defined using the `BatchIssueCreateRequest` format:

```json
{
  "repository": "owner/repo",
  "issues": [
    {
      "localId": "epic-1",
      "type": "Feature",
      "title": "Epic Title",
      "body": "Epic description in markdown",
      "labels": ["feature"],
      "assignees": [],
      "parent": null,
      "dependsOn": []
    },
    {
      "localId": "task-1",
      "type": "Task",
      "title": "Task 1 Title",
      "body": "Task description",
      "labels": ["task"],
      "assignees": [],
      "parent": "epic-1",
      "dependsOn": []
    },
    {
      "localId": "task-2",
      "type": "Task",
      "title": "Task 2 Title",
      "body": "Task description",
      "labels": ["task"],
      "assignees": [],
      "parent": "epic-1",
      "dependsOn": ["task-1"]
    }
  ]
}
```

## Field Descriptions

### Required Fields

- **localId** (string): Unique identifier within this batch, used for referencing in `parent` and `dependsOn` fields
- **type** (string): Issue type - one of: `Feature`, `Task`, `Bug`, `Spike`
- **title** (string): Brief summary of the issue (one line)
- **body** (string): Detailed description in markdown format

### Optional Fields

- **labels** (array): Tags/labels to apply to the issue (default: `[]`)
- **assignees** (array): GitHub usernames to assign the issue to (default: `[]`)
- **parent** (string): localId of parent issue for hierarchical issues (default: `null`)
- **dependsOn** (array): localIds of issues that must be completed before this one (default: `[]`)

## Issue Types

- **Feature**: Epic-level work - large feature or initiative
- **Task**: Individual work item - concrete task to be completed
- **Bug**: Defect tracking - bug or error to be fixed
- **Spike**: Research/investigation - exploratory work or proof of concept

## Hierarchies and Dependencies

### Parent-Child Relationships

Use `parent` to create hierarchical issues:

```json
{
  "issues": [
    {
      "localId": "epic",
      "type": "Feature",
      "title": "Authentication System",
      "parent": null
    },
    {
      "localId": "task-1",
      "type": "Task",
      "title": "Implement login endpoint",
      "parent": "epic"
    },
    {
      "localId": "task-2",
      "type": "Task",
      "title": "Implement logout endpoint",
      "parent": "epic"
    }
  ]
}
```

### Dependencies

Use `dependsOn` to specify task dependencies:

```json
{
  "issues": [
    {
      "localId": "task-1",
      "type": "Task",
      "title": "Design database schema",
      "dependsOn": []
    },
    {
      "localId": "task-2",
      "type": "Task",
      "title": "Implement migrations",
      "dependsOn": ["task-1"]
    },
    {
      "localId": "task-3",
      "type": "Task",
      "title": "Write tests",
      "dependsOn": ["task-1", "task-2"]
    }
  ]
}
```

## How It Works

1. **Topological Sorting**: Issues are automatically sorted so parents and dependencies are created before the issues that reference them
2. **Parent References**: Parent-child relationships are documented in issue bodies with "Part of: #N"
3. **Dependency Documentation**: Dependencies are added to issue descriptions
4. **Child Summaries**: Parent issues get a comment listing all their child issues

## Example Files

- **pm-agent-epic.json**: Example of a feature epic with 8 subtasks, showing hierarchies and dependencies

## Prerequisites

You must be authenticated with the GitHub CLI:

```bash
gh auth login
gh auth status
```

## Tips

1. Use descriptive `localId` values that make dependencies clear (e.g., "epic-auth", "task-login", "task-logout")
2. Keep titles concise but descriptive
3. Use markdown in the `body` for formatting, code blocks, checklists, etc.
4. Always test with `--dry-run` first to validate your JSON
5. Structure epics logically with clear task breakdowns
6. Use `dependsOn` to ensure tasks are completed in the right order
