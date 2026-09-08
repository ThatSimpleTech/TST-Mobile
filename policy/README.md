# Policy pack

`policy.yaml` is the decision policy for TST Assist (plan §4). It is data, versioned, and
refused to the agent by the rules-file boundary. `cases.yaml` is the conformance suite:
every enforcer that claims to apply this pack must reproduce every case exactly, tier and
rule id both.

Today there is one enforcer, in Kotlin (`core/.../policy/PolicyEnforcer.kt`), tested by
`PolicyConformanceTest`. A second enforcer in tstd would load the same two files; that is
the plan's "one policy, two enforcers" and it is not built yet.

## How a decision is made

1. Derive facts from the action, the screen and the task: which verb, whether it acts on
   the device, whether the target is a password field or a sensitive control, whether the
   app is allowlisted, whether it is in the task's goal app set, whether the screen is on
   the keyguard or secure.
2. Walk `rules` in order. A rule matches when every key in its `when` block is true.
   The first match wins. No match means `default`.
3. The tier says what gate applies:

| Tier | Gate |
|---|---|
| `silent` | run |
| `once` | run if the task already has its one-time grant, else ask for it (one card covering the task) |
| `every` | a card every time, at most `max_tier2_per_turn` per turn |
| `refused` | never, and no approval can change that |

## Relationship to TST Desk's classifier

TST Desk classifies tool calls into A/B/C on a reversibility axis, then resolves
auto/ask/never. That classifier is Python code (15 predicates), not data, and it falls back
to a model call for ambiguous tool calls. This pack is deliberately rules-only: a
bring-your-own-model phone cannot lean on the model to be careful. The mapping is
`silent ≈ A/auto`, `every ≈ B/ask`, `refused ≈ never`; `once` has no desktop equivalent.
