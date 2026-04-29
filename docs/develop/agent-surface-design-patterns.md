# Designing for agents that render UI

An agent that renders UI is in a strange position. It is making decisions
under uncertainty, all the time, and every now and then it pauses and asks a
person for help. The question is which moments deserve that pause — and what
shape the pause should take when it happens.

`AgentSurface` gives you four shapes: Form, Choice, Confirmation, Card. The
contract enforces that the agent picks one. This essay is about *how* to pick
— and how to write the words inside the shape so the person on the other end
knows what they're being asked.

## When to ask, when to act

The cheapest way to fail is to ask too much. Every prompt is friction; every
prompt is a context switch from whatever the user was doing. A surface
should appear because the agent is genuinely uncertain or genuinely about to
do something the user would want to know about — not because the agent
wants reassurance.

Three cases where a surface is the right answer:

1. **The next step is irreversible.** A force-push, a delete, a
   production deploy. The user needs to confirm, even if the agent is
   confident.
2. **The agent has multiple plausible paths and no signal to discriminate.**
   "Which base branch should this PR target?" — the answer depends on
   intent the agent can't see in the diff.
3. **The agent has a result the user asked for and needs to surface it.**
   "Here's the PR I opened" is the close of a loop the user started.

Three cases where a surface is the wrong answer:

1. **Asking for permission to do the thing the user just asked for.**
   "I'm about to run the tests you told me to run — proceed?" is noise.
   The user's instruction is the consent.
2. **Re-asking when the answer is already in scope.** If the user's
   request specified the base branch, don't show a `Choice` of branches.
   Read the request.
3. **Surfacing a status that nothing depends on.** "I started the build"
   is a log line, not a card. Render the *result* of the build, not the
   ignition.

The shorthand: a surface should be either a *decision* or a *delivery*.
If it's neither, it's interrupting the user for nothing.

## Picking the right variant

The variants form a small taxonomy. Match the shape of the question to the
shape of the variant.

| Question | Variant |
| --- | --- |
| "Should I do this irreversible thing?" | `Confirmation` |
| "Which of these should I pick?" | `Choice` |
| "Fill in the values I need to proceed." | `Form` |
| "Here is the result; what (if anything) should I do next?" | `Card` |

A few mismatches show up often enough to call out:

- **A `Confirmation` with three buttons isn't a `Confirmation`.** It's a
  `Choice`. `Confirmation` is yes-or-no; once you have a third path,
  switch.
- **A `Choice` with two options labelled "Yes" / "No" isn't a `Choice`.**
  It's a `Confirmation` in disguise. Use the variant whose semantics
  match — `Confirmation` carries severity and destructive-affordance
  contracts that `Choice` does not.
- **A `Form` with one optional `Toggle` isn't a `Form`.** It's a
  `Confirmation` with extra steps. Either the toggle matters (in which
  case make it a real branch) or it doesn't (in which case drop it).
- **A `Card` with a "Continue" button isn't a `Card`.** It's a status
  modal. Either the user has something meaningful to pick (make the
  buttons describe outcomes) or the card is read-only (drop the button).

## Urgency and severity

Two knobs influence how prominent the surface feels: `Urgency` on the bus
event itself (Low, Medium, High) and `Severity` on `Confirmation` (and on
`Card.Action`). They do different jobs.

**Urgency** is about *when* the user sees it. A high-urgency surface should
interrupt; a low-urgency one can sit in a queue. The bus carries the urgency;
the platform layer decides what to do with it. From the Plugin's side, the
rule is: only emit `HIGH` for things that genuinely cannot wait.

**Severity** is about *what the next click means*. `Destructive` says "this
button takes the user down a one-way street." Renderers map it to whatever
their platform's destructive affordance is. The button still confirms
something — but the visual weight and the muscle memory ("red is forever")
are doing the work of warning the user.

The mapping in practice:

- **Routine confirmation, recoverable outcome** — `severity = Info`,
  `urgency = MEDIUM`. Default for "is this what you wanted?" prompts.
- **Attention needed, recoverable outcome** — `severity = Warning`,
  `urgency = MEDIUM`. The user might overwrite something; they can fix it.
- **Irreversible outcome** — `severity = Destructive`,
  `urgency = MEDIUM` or `HIGH`. The next click can't be taken back.
- **Background information** — `Card`, `urgency = LOW`. The user can
  read it on their own schedule.

There is no `Severity = Trivial` and no `Urgency = NUCLEAR`. The agent's
job is to make the call honestly inside the levels the contract gives.

## Writing the prompt

The prompt is the part the user actually reads. Treat it like writing
documentation for a stranger.

**State the action, not the state.** "Delete the branch 'feature/old'?" is a
question with a verb and an object. "Confirm branch deletion?" is corporate
ceremony. The user is deciding to *do something*; the prompt should name
what.

**Name the thing being acted on.** Specific quoted object names beat generic
descriptions every time. `"Delete the branch 'feature/old'?"` beats `"Delete
the selected branch?"`. The user is reading fast; concrete names anchor.

**Include the consequence if it's not obvious.** `"Force-push 'main'?
Anyone with this branch checked out will need to reset."` is the difference
between an informed consent and a regret.

**Don't ask twice in one prompt.** A prompt with "Continue?" tacked onto the
end is teaching the user that the button was the question all along. Pick
one.

**Avoid hedge words.** "Are you sure you want to maybe delete this?" is the
agent's anxiety leaking into the user's experience. Either ask the question
or don't.

## Writing labels

Labels are the contract between the user's intent and the agent's action.

**Use the verb of the outcome.** `"Delete"` and `"Keep"`. `"Force-push"` and
`"Stop"`. The button is what the user is committing to — the label should
describe that commitment in two or three words.

**Don't use `"OK"` and `"Cancel"`.** Those are dialog labels, not action
labels. They tell the user nothing about what's about to happen if they
press the button. The contract's defaults are placeholders; replace them.

**Symmetry helps.** `"Delete" / "Keep"` reads as a pair of choices. `"Yes,
delete the branch" / "No"` reads as a confession.

**Imperatives, not nouns.** `"Open PR"`, not `"Pull request"`. `"Schedule
deploy"`, not `"Deployment"`. The label is what the user is *doing*.

## Validation copy

Validation messages live inside `FieldValidationResult.Invalid(errors)`. The
contract gives you a `List<String>` and lets the renderer surface them
inline. Same writing rules:

**Lead with what's wrong.** `"Branch name must be at least 3 characters"`,
not `"Please enter a longer branch name"`. The first three words tell the
user the problem.

**Name the field.** The default validators do this for you (`"$label is
required"`). If you write a custom error string, keep the pattern — it's
what makes errors readable when several fields fail at once.

**Be specific about the rule.** `"at least 3 characters"` is testable;
`"too short"` is editorial. The field already knows what `minLength` is
because that's how the validator failed; pass it through.

**Don't apologise for the rule.** `"This field is required, sorry!"`
trains the user that the rule is arbitrary. It isn't. State the rule;
trust the user to accept it.

## Three rough patterns

A few combinations come up enough to be worth naming.

### Confirm-then-show

A `Confirmation` followed by a `Card` carrying the result. The agent
asks before the irreversible step; the agent shows the outcome after.
The two surfaces share *no* state — the `correlationId` on each is
independent, because they are separate decisions on the user's part.
Cancel on the `Confirmation` short-circuits; the `Card` never emits.

### Pick-then-form

A `Choice` followed by a `Form`. The user picks the kind of thing they
want to do; the form gathers the parameters for that kind. Don't try to
collapse this into one giant form — the second surface only needs the
fields that apply to the picked option, and you'd be hiding fields with
runtime visibility logic that the contract can't see.

### Form-with-confirm-on-submit

A `Form` for the input, then a `Confirmation` on submit if the values are
destructive. The form validates the inputs; the confirmation gates the
irreversible step. This separation is what lets the user fix typos in
the form without re-confirming each time.

## What renderers will do

The contract is `commonMain`-only by design — Plugins describe the surface,
renderers translate it. That means you don't get pixel-level control, and
that's the point. A `Confirmation` with `Destructive` severity will look
*right* on Android, on iOS, in a desktop window, in a CLI — because each
renderer maps the contract to the affordance the user already knows.

If you find yourself reaching for renderer-specific styling, the surface
contract is probably missing something. The right move is to extend the
contract (a new severity? a new slot kind?) — not to encode the styling in
the prompt text.

<!-- platform-notes: pending W1.3 / W1.4 -->
<!-- example: pending W2.1 -->
