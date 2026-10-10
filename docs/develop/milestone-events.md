# Milestone Events

`MemoryEvent.MilestoneReached` is the low-volume event for significant agent
checkpoints. It is a sibling of `KnowledgeStored`, not a flag on it, so bridge
and renderer consumers can subscribe to `MilestoneReached.EVENT_TYPE` without
filtering every routine memory write.

## Publish Sites

The built-in `MilestoneTracker` is attached to `AgentEventApi` and keeps
process-local state for that agent. APIs created from the same
`AgentEventApiFactory` share the same per-agent state:

| Category | Trigger |
| --- | --- |
| `FIRST_SUCCESS` | First `TaskCompleted` for a new `taskType` observed from that agent. If `taskType` is not supplied, the task id is used as the local type key. |
| `RECOVERY` | `TaskFailed` followed by `TaskCompleted` for the same `taskId` from that agent. |
| `EXTERNAL` | Explicit call through `AgentEventApi.reachMilestone(...)` or `ObservableAgent.reachMilestone(...)`. |

`KEY_INSIGHT` and `CHECKPOINT` are reserved for future publish sites.

The tracker only fires if something publishes the task lifecycle it listens for. Use
`AgentEventApi.openTaskLifecycle(...)` (`agents/events/api/TaskLifecycle.kt`) rather than a
bare `publishTaskCreated`: it publishes `TaskCreated` and `TaskStarted`, and its `completed` /
`failed` are the `TaskCompleted` / `TaskFailed` the table above keys off. Pass a `taskType`
that names the *kind* of work — one constant per workflow, not one per instance — or every task
is its own `FIRST_SUCCESS`.

## Bridge Reference

The Lumos bridge can map AMPERE milestones to STAR by subscribing to:

```kotlin
eventBus.subscribe<MemoryEvent.MilestoneReached, EventSubscription.ByEventClassType>(
    agentId = bridgeAgentId,
    eventType = MemoryEvent.MilestoneReached.EVENT_TYPE,
) { event, _ ->
    // milestone -> STAR
}
```

The event carries `category`, `description`, optional `knowledgeId`, optional
`taskId`, and optional `runId`, which is enough for the bridge to render a STAR
and retain provenance back to the agent run.
