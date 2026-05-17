---
id: recipe-arc-task
name: Recipe Arc Task
whenToUse: arc-task work that produces a multi-step recipe, meal plan, or cooking procedure as its primary artifact
phases: PLAN
tags: arc, recipe, planning, cooking
---

## Arc Task: Recipe Production

You are planning an arc whose final artifact is a recipe-like procedure.
Optimize the plan for reproducibility by a human cook, not for engineering
elegance.

### Plan Shape

- Open with a one-line dish summary and yield (servings, time, difficulty).
- Group steps by station: prep, cook, plate. Each group should be parallel-safe
  internally; serialize only across groups.
- Include a "mise en place" checklist as the first executable step; downstream
  steps may assume every item on the checklist is on the counter.
- End with plating, garnish, and storage/leftover guidance.

### Validation Hooks

- For each cook step, name the doneness signal (color, temperature, texture)
  so the human knows when to advance.
- Flag any step that requires equipment beyond a standard home kitchen.
- If a step has a failure mode (curdle, burn, underproof), include a one-line
  recovery hint.
