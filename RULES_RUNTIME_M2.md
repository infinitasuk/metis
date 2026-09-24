# M2 — Headless rules runtime

**Delivered and locally verified, 2026-09-20.** The combined clean full/mutation run
passed 246 Java tests, 242 generated/exhaustive property checks, language/runtime
coverage gates, SQL/API/Chromium regressions and cleanup verification. Focused mutation
testing detected 194/198 mutants (98%). A subsequent core verification also passed
fresh compiler/runtime reconstruction between every script command. Hosted CI is
configured; its remote results are separate from this local verification.

## What runs now

`RulesRuntime` executes compiled definitions through registered core
instructions. Resource race and hidden-grid targeting both run from setup to a
definition-declared terminal outcome through the same interpreter. There is no call
into `AuthoritativeGameEngine`, its action switch, unit profiles or activation adapter;
an architecture test enforces that boundary.

This remains a framework-independent, in-process authoritative runtime. M3 now wraps it
with durable transactions, authenticated HTTP commands, idempotency and bounded reaction
windows; see `RULES_API_M3.md`. M4 now adds dice, cards, resources and effect handlers
through optional modules (`RULES_MECHANICS_M4.md`). The existing
web battle simulator still uses its captured legacy/activation contracts.

## Run the demonstrations

From the repository root:

```text
mvn -q -pl engine-core -Prules-play compile exec:java
mvn -q -pl engine-core -Prules-play "-Drules.source=engine-core/src/main/resources/rules/examples/hidden-grid.json" "-Drules.script=engine-core/src/main/resources/rules/scenarios/hidden-grid-play.json" compile exec:java
```

The default uses resource race. Both demonstrations:

- Compile a JSON definition and read a separate, data-only command/decision script.
- Restore the checkpoint after every accepted submission.
- Check an expected rejection (insufficient resources or a repeated cell guess).
- Finish with the specified outcome/winners/round, or exit with an error.

The command provider in `RulesPlayCli` has no game-ID branch. It converts script
arguments using the compiled action types and sends ordinary runtime commands.
Scripts live under `engine-core/src/main/resources/rules/scenarios/` and are test/demo
decision providers, not a privileged production CPU strategy.

## Public Java contract

Package: `com.metis.engine.core.rules.runtime`.

```java
var compiled = new RulesCompiler().compile(json);
if (!compiled.valid()) throw new IllegalArgumentException(compiled.diagnostics().toString());
var runtime = new RulesRuntime(compiled.definition());
var state = runtime.initial("example-session", List.of("alice", "bob"));
var started = runtime.start(state, state.version());
state = started.state();

// Caller submits a logical player, exact typed parameters and the expected version.
var result = runtime.act(state, "alice", "collect",
    Map.of("counter", RuleValue.entity("counter", "alice")), state.version());
```

Commands:

| Method | Meaning |
| --- | --- |
| `initial(sessionId, players)` | Explicit ordered, unique player identities and definition initial fields |
| `start(state, version)` | Run setup, then enter the initial phase |
| `act(state, player, action, inputs, version)` | Validate ownership policy/eligibility/inputs/budget and run its procedure |
| `respond(state, player, decisionId, optionIndex, version)` | Resume the exact pending decision with an offered choice |
| `activate(state, player, entityId, version)` | Select an eligible subject in an entity-activation phase |
| `advanceStep(state, player, version)` | Skip the optional remainder of a step after its minimum is met |
| `pass(state, player, version)` | Complete a turn/activation after all remaining mandatory work is satisfied |
| `preview(state, player, action, inputs)` | Check concrete input eligibility without effects or state changes |
| `candidates(state, player)` | Candidate action forms and input types permitted by the current step/turn |
| `eligibleEntities(state, player)` | Resolve the definition's entity selector for activation |
| `restore(json)` | Decode and validate a trusted authoritative checkpoint |

Candidate forms are not a claim that every possible argument is legal. Arbitrary
string/numeric inputs do not have an invented finite domain; clients supply them and
use `preview`, or definitions request finite `core.choose` decisions. Preview checks
inputs, eligibility and budgets, not speculative execution of all subsequent effects.
An effect may still reject (for example, an invalid restoration or resource update).

An accepted result has a new state/version and recorded `Commit`; its code is
`ACCEPTED`, `WAITING_FOR_DECISION` or `GAME_FINISHED`. Rejections have stable codes,
no resulting state and no commit. The input checkpoint is immutable in both cases.
Logical actors are checked against the game; authenticated application-user-to-seat
binding is an API responsibility that will be integrated in M3.

## Execution and atomicity

- The interpreter works on an isolated transition copy, preserving the supplied state.
  Any failed instruction discards all changes/events/traces from that submission.
- Ordered block frames have a source block path, next instruction index, lexical
  locals and call depth. Calls have fresh parameter scope; branch and loop locals do
  not escape. For-each captures its bounded input list and iteration cursor.
- `core.choose` increments the instruction cursor before suspension. The continuation
  captures frames, initiating actor/action, reserved action step and remaining budget.
  Choosing an option binds it and continues after the choice; preceding costs/effects
  do not run again.
- A choice can target a player other than the initiating actor. Only that responder
  can answer. Decision resolution events identify the responder; subsequent effects
  and action-budget credit retain the original initiating actor.
- A procedure spanning choices spans multiple accepted transitions. Effects committed
  before a choice remain committed if a later response fails; that failed response
  leaves the prior pending checkpoint intact. This is not one transaction held open
  across player input.
- The action budget is credited once after procedure completion, including an action
  that ends the game. No unrelated command may execute while a decision is pending.

Each transition shares a budget across instructions, expression evaluation, loop and
phase progression. Pending decisions carry the remaining budget; answering cannot
renew it. Dynamic work can exceed a compiled structural bound and rejects explicitly.
Event count is capped at 100,000. Checkpoints are limited to 16,000,000 characters;
the bounded writer checks before acceptance so oversized results roll back. The core-only
contract contains no randomness; M4 persists it inside optional mechanics. There are
no implicit universal victory or resource-refresh effects.

## Phase and activation semantics

All player order derives from the supplied player list, never a game or faction name.
Phases follow `next`; entering `roundStart` begins the next round. An introductory
phase before the round marker uses round 0. Entry procedures can themselves suspend.

- **ALTERNATING_PLAYERS:** one player completes the ordered step budgets, then the
  next unfinished player acts. Once all finish, follow `next`.
- **ALTERNATING_ENTITIES:** select an eligible entity, execute its budgets, then
  hand over after `groupSize` entities or exhaustion. Skip players with no eligible
  entities; an unexhausted player can receive control again.
- **PLAYER_PHASE:** a player completes its eligible entities before handing over.
  `groupSize` does not shorten a whole-player phase.
- **SIMULTANEOUS:** unfinished players may submit in any order; each has independent
  step use. Advance only when every player completes. Private field visibility is
  preserved. Accepted submissions are still serialized, and a pending `choose` pauses
  unrelated submissions; distributed concurrent windows belong to M3.
- **MANUAL:** each participant completes or passes its optional work; all must finish
  before the shared phase advances. An empty manual phase still requires these passes.
- **AUTOMATIC:** execute the entry procedure, then follow `next`.

Reaching a step's maximum moves to the next step. Taking an action from a later step
forfeits optional earlier work only after required minima have been met. Reaching the
last maximum completes the activation automatically; `pass` can finish earlier when
all minima are met. Removed or newly ineligible active subjects close their activation.
Completed entity IDs cannot activate again in that phase; the ledger resets next phase.
No unit health, weapon type, faction, movement or command names participate in this logic.

### Explicit entity subjects

An entity phase now requires `entityParameter`, naming an entity-typed parameter
declared by every action in its steps, for example `"entityParameter":"counter"`.
The action's subject must equal the selected active eligible entity. Other parameters
can still identify targets. This closes the ambiguity where one entity could be
selected while another consumed its activation budget.

M2 introduced compiler contract **`rules-compiler-2`**. Language-v1 examples with player
turns remain valid; entity-phase definitions must add the explicit subject binding.
M1 had no published runtime sessions. M2 checkpoints were tagged **`rules-runtime-1`**.
Core-only persisted sessions retain `rules-compiler-3` / `rules-runtime-2`. M4 mechanical
sessions use 4/3. Checkpoints are bound to the compiled fingerprint and unsupported
versions fail explicitly.

## Checkpoints, replay and visibility

`RulesState` and `Commit` are authoritative server data. They contain private state,
frames, choices and patches and must not be served directly to players. The codec uses
typed values, rejects unknown/malformed fields, duplicate JSON keys, trailing documents,
numeric coercions and oversized/deep inputs. Restore validates declaration fields,
resource bounds, progress, frames/lexical scopes, decision origin and event sequence.
It is not a signature or authorization system for accepting client-edited saves.

`RulesRuntime.replay(before, commit)` applies recorded field/entity/control deltas and
events, checking identity and sequence. It does not run procedures, queries or current
catalogue rules. Replaying every recorded commit reconstructs the accepted checkpoints,
including intermediate pending decisions. Trace rows identify source operation/path;
they remain authoritative diagnostic data, not public events.

Use `RulesView.project(definition, state, controlledPlayers)` for player-facing state:

- PUBLIC fields/resources/events are visible to everyone.
- OWNER fields/resources and private decisions/events require the corresponding seat.
- SERVER fields, frames, patches and traces are omitted.
- Hidden events retain sequence positions as placeholders.
- Entity identity/type/owner and decision-waiting metadata are public in this v1 model;
  whole-entity concealment remains separate work. M3 applies this projection across
  state, command responses, events, history and idempotent replies.
- Visibility follows the declared output policy. An authored public event can explicitly
  reveal information; there is no claim that private choices prevent deductions from
  deliberately public effects.

## Automated acceptance

```text
mvn -B -pl engine-core verify
mvn -B -pl engine-core -Prules-mutation clean verify
mvn -B "-Prules-full,rules-mutation" clean verify
```

Tests cover both complete games, JSON-only goal/name/phase changes, private setup,
every-checkpoint restore and event replay, independent actor/entity budgets, uneven
groups, manual/simultaneous/automatic phases, required passes, nested call/branch/loop
choices, cross-player responses, atomic failures, stale requests, malformed saves,
numeric/resource/collection/checkpoint limits and non-renewable execution budgets.
Generated goals and grid placements supplement fixed independent expected outcomes.

Both language and runtime packages have 90% line / 85% branch coverage gates. Focused
mutation testing now includes runtime reference/resource checks and the procedure/flow
machine. M3 now adds real persistence interruption, authenticated API delivery and
concurrency/idempotency tests; in-memory version checks alone are not a database lock.
