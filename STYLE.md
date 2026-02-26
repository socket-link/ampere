# STYLE.md

Style guide for all Ampere content: documentation, README, code comments, error messages, agent prompts, and external communications.

## Voice

**A sharp engineer with the curiosity of a scientist.** Precision and wonder in equal measure. States what's true without hedging. Creates imagery. Intelligence through clarity, not complexity. Excited about elegance, not features. Respects the reader's depth. Never condescends, never oversells.

## Tone by Context

| Context | Tone | Example |
|---------|------|---------|
| README / hero | Evocative, confident | *"Every AI system today shares the same limitation: the space between input and output is dark."* |
| Technical docs | Clear, warm, opinionated | *"Events are the fundamental primitive. If you're reaching for a direct method call, pause and ask what event this should emit."* |
| API reference | Minimal, exact | *"`AgentTeam.pursue(goal: String)` -- Initiates the PROPEL loop across all agents with shared context."* |
| Error messages | Honest, helpful | *"Confidence below escalation threshold (0.34). The agent is uncertain about OAuth2 implementation and is requesting human input."* |
| Social / short-form | Sharp, visual | *"Most AI agents are black boxes. Here's what it looks like when you can see one think:"* |

## Language Principles

**Lead with imagery.** Make the reader *see* it before explaining it. The event stream, the cognitive loop, the moment of uncertainty surfacing -- these are visual experiences, not feature descriptions.

**Every sentence earns its place.** If it doesn't teach, reveal, or inspire, cut it.

**Precision is poetic.** "Electric charge in motion per unit time" is more evocative than any creative copy because it's exact. Technical accuracy, when the underlying system is elegant, creates its own beauty.

**Opinions are stated, not argued.** "Events are the fundamental primitive" -- not "we believe events are a good approach to consider."

**Biological language for behavior, electrical language for structure.** Agents *perceive, recall, decide.* The system *conducts, routes, transmits.* The cognitive loop *fires.* The event bus *carries current.*

## Vocabulary

### Prefer

| Use | Instead of |
|-----|-----------|
| transparent | explainable |
| visible / legible | observable (acceptable in technical contexts) |
| cognitive | intelligent |
| current / flow | pipeline / workflow |
| signal | message (when describing events) |
| surface | expose / reveal |
| form | generate / produce (when describing decisions) |
| architecture | platform / solution |
| uncertain | confused / stuck |
| cultivate | deploy / manage (when describing agent teams) |
| production-ready | enterprise-grade |

### Avoid

| Word | Why |
|------|-----|
| revolutionary / game-changing | Hype. Let the work speak. |
| leverage / utilize | Corporate filler. Use *use.* |
| seamless / frictionless | Overused to meaninglessness. |
| AI-powered | Everything is. Say what it does. |
| enterprise-grade | Implies boring. |
| democratize | Vague and overused. |
| delve / dive into | AI-writing tells. |
| out of the box | Say what works by default. |
| robust | Means nothing. Say what it survives. |
| next-generation | Says nothing about what generation this is. |

## The Bioelectric Model

Ampere's naming fuses biological cognition with electrical systems. Neural signals are electrical. Thought is current. The brain is a circuit. This is not metaphor -- it's the architecture.

### Existing Terms

| Term | Function | Bioelectric Basis |
|------|----------|-------------------|
| **Spark** | Context modifier shaping cognitive specialization | Action potential triggering neural differentiation |
| **Arc** | Defined orchestration pathway | Electrical arc -- current flowing along a defined path |
| **PROPEL Loop** | Cognitive cycle (Perceive, Recall, Optimize, Plan, Execute, Loop) | Neural firing cycle -- stimulus, integration, response, feedback |
| **EventSerializerBus** | Central nervous system carrying signals | Axon bundle -- impulses traveling between brain regions |
| **Memory Cell** | Persistent knowledge with provenance | Biological memory encoded in synaptic weights |
| **Escalation** | Uncertainty surfacing to a human | Pain signal -- requesting external intervention |

### Naming New Concepts

When naming a new component, ask in order:

1. What does this do in the system? (function)
2. What is the biological equivalent? (cognition)
3. What is the electrical equivalent? (physics)
4. Where do those two converge? (the bioelectric term)

If biological and electrical point to the same word, that's the name. If they diverge, prefer the term a developer would understand without explanation.

## Visual Identity

### Colors

| Color | Hex | Role |
|-------|-----|------|
| **Electric Purple** | `#5639DE` | Primary -- deep processing, the substrate of thought |
| **Cerebral Blue** | `#24A6DF` | Secondary -- active cognition, the signal in transit |
| **Signal Amber** | `#FFA736` | Accent -- awareness, escalation, human-facing moments |

Ratio: ~60% purple/dark, ~30% blue/active, ~10% amber/signal.

### Principles

- **Dark-first.** Cognition is luminous against darkness. You're looking into the mind.
- **Motion implies life.** GIF over screenshot. Video over GIF. Interactive demo over video.
- **Density signals depth.** Show real complexity in a legible way. Dense but navigable, like a cockpit.
- **Monospace** for system output and code. **Sans-serif** for everything else. Precise and contemporary.

## Brand Principles

1. **Transparency is the product.** Not a feature. The architecture.
2. **Show, don't describe.** A GIF of the event stream beats any paragraph about it.
3. **Precision is beauty.** Technical accuracy creates its own poetry.
4. **Opinions earned through building.** State convictions. Don't hedge.
5. **Complement, don't compete.** Ampere is the missing layer, not a replacement.
6. **The bioelectric model is literal.** The architecture mirrors biological cognition. When someone says "that's just a metaphor," the answer is "no, it's the architecture."
7. **Current flows.** Everything should feel alive, in motion. Static is death.
