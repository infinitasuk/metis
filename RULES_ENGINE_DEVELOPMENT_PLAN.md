# Rules-driven engine — primary development plan

Date: 2026-09-19. **Priority: primary product delivery track.**
Status: **M0–M7 implemented with local verification; hosted CI release qualification pending.** The interpreter
now has durable APIs, reaction windows, configurable dice, cards, resource/queue modules
and timed effects, shared spatial/scenario modules and three complete projected-CPU
games. M6 adds pinned mode resolution and the first bounded catalogue slice.
M7 adds the generic browser and persistent CPU seats. See `RULES_CLIENT_M7.md` for
client/release scope and `RULES_MODES_M6.md` for catalogue compatibility.
The full suite, coverage gates and focused mutation job are configured in CI;
hosted execution remains separate from the successful local checks.

M0 evidence: [RULES_ENGINE_M0_BASELINE.md](RULES_ENGINE_M0_BASELINE.md) contains the
22-item hardcoding inventory and v1 scope. [Runner instructions](tools/rules-tests/README.md)
describe the verified clean-build command, deterministic scenarios, mandatory browser
checks and intentional failure/cleanup probe. Hosted CI is configured, pending its
first repository execution.

M1 evidence: [RULES_LANGUAGE_V1.md](RULES_LANGUAGE_V1.md) documents the versioned
schema, immutable compiled plans, typed expressions and module/query contracts.
Both packaged game examples compile. A clean combined full/mutation run passed
208 Java tests, including two properties exercising 200 generated cases, all existing
integration stages and the cleanup probe. Coverage gates are 90% line / 85% branch
for the new package; focused mutation detected 77/79 mutants (97%).

M2 evidence: [RULES_RUNTIME_M2.md](RULES_RUNTIME_M2.md) documents commands, execution
frames, phase policies, subject binding, projection and replay. Both games finish,
including JSON-only variants and fresh-runtime restoration. The expanded suite passed
246 Java tests and 242 property checks; focused mutation detected 194/198 mutants (98%).
Coverage gates apply separately to both language and runtime packages.

M3 evidence: [RULES_API_M3.md](RULES_API_M3.md) documents V36, publication, captures,
projected APIs, atomic checkpoints/commits/receipts and reaction windows. The expanded
suite passed 253 Java tests, 242 property checks, SQL/API/browser regressions, concurrent
commands, injected rollback, lost HTTP acknowledgements and actual application kills
both at a pending decision and during an uncommitted write. Focused mutation detected
206/213 mutants (97%). New APIs and V36 are deployed locally.

M4 evidence: [RULES_MECHANICS_M4.md](RULES_MECHANICS_M4.md) documents weighted dice,
player-selected modifications, private card piles, resources, queues and timed effects.
The suite passed 290 Java tests, 242 property checks and post-roll restart/rollback
checks. Final selected mutation detection was 338/372 (91%). V37 protects random-stream
identity/cursor updates, and core-only captures retain their existing contracts.

## Product decision

Build an actual executable rules engine before extending the battle experience.
Game/mode JSON describes behavior; a shared interpreter and versioned mechanical
modules execute it. Supported games must not require changes to engine, UI or CPU
code merely to change phases, dice, token meanings or timing.

During this track:

- Defer standalone battle UI enhancements, game-specific combat patches and advanced
  CPU strategy. F08–F22 mechanics enter through interpreter/module contracts.
- Continue fixes required to keep existing sessions usable and to protect persisted
  state and access control. Such maintenance does not replace milestone work.
- Build only the API/test client needed to prove the runtime until the engine gates
  pass. A thin generic player interface is a later integration milestone.
- Reuse snapshots, sequencing, authorization, projection and order primitives where
  appropriate; do not mistake reuse of a legacy adapter for a fully migrated engine.
- Deliver tests with each capability. An implementation milestone is incomplete
  until its automated acceptance gate passes in CI.

This plan owns delivery order. [RULES_LANGUAGE_BACKLOG.md](RULES_LANGUAGE_BACKLOG.md)
owns R-task details; [RULES_ENGINE_TEST_PLAN.md](RULES_ENGINE_TEST_PLAN.md) owns the
test matrix and automation gates. The existing F-backlog records behavior corrections.

## Target execution path

```text
Game JSON + mode overrides + pinned module versions
  -> schema/type/reference validation
  -> normalized, compiled rules definition
  -> immutable session capture
  -> authoritative interpreter
       -> legal actions / decision schemas / previews
       -> reusable mechanical operations
       -> committed events + execution continuation + state
  -> seat-projected API -> generic UI or CPU decision provider
```

The runtime must distinguish complete, waiting-for-decision, rejected and failed
execution. A continuation contains rule/procedure identity, instruction position,
frames/locals, pending decision and its audience. Each submission/resumption commits
its state, events, randomness bookkeeping and continuation atomically. A procedure
may span multiple committed decisions; it does not hold a database transaction open
while a player thinks.

The core stays framework-independent. Use `engine-core` for language/runtime/module
contracts, `engine-api` for publication/session/persistence boundaries, `database`
for migrations and routines, and `webapp` for the later generic client. Separate
services or additional clients are not prerequisites for proving this architecture.

## Milestone overview

| Milestone | Working outcome | Backlog coverage | Depends on |
| --- | --- | --- | --- |
| M0 | Agreed v1 scope, hardcoding inventory and automated test/CI foundation | R01; test infrastructure for R20 | None |
| M1 | JSON definitions compile with precise validation errors | R02–R06 | M0 |
| M2 | A headless interpreter completes two different no-dice games | R07/R09; initial R08/R18 | M1 |
| M3 | Real API sessions persist, pause, react and resume reliably | R08/R10; API portion of R16; R18/R19 | M2 |
| M4 | Dice, tokens, cards and timed effects execute from JSON | R11/R12 | M3 |
| M5 | Spatial modules and complete contrasting games run headlessly | R13/R14; headless R17/R20 | M4 |
| M6 | Modes and migrated catalogue definitions use pinned rules | R15/R19; remaining R18 | M5 |
| M7 | Generic player UI/CPU integration and release qualification | R16/R17/R20 | M6 |

R21's richer rules editor follows M7. JSON files and publication/validation APIs
are sufficient for initial authoring. No calendar commitments are assumed; estimate
each milestone after the preceding gate exposes the actual implementation scope.

## M0 — Scope, architectural baseline and test infrastructure

**Deliverables**

1. Inventory hardcoded decisions in core, API, CPU and client; map them to definition
   data, interpreter instructions, module contracts or legacy-only compatibility.
2. Record architectural decisions for the v1 type system, bounded execution,
   state/event boundaries, version compatibility and the first two example games.
3. Define the supported-v1 feature checklist. Unsupported operations fail validation;
   there is no silent fallback to a legacy game routine.
4. Add the common scenario fixture format, deterministic clocks/IDs/random inputs,
   isolated PostgreSQL/app lifecycle, CI jobs and report/artifact collection.
5. Integrate the existing Java, SQL and multiplayer checks into repeatable automation.
   The current manually launched Node checks are a starting point, not the full suite.

**Automated exit gate:** a clean checkout runs current regressions in an isolated
environment; both successful and deliberately failing fixture examples prove the
harness detects assertions and propagates failures. It needs no existing database,
manual login, global Node packages or manually chosen session IDs.

**Demonstration:** one documented command executes the available full suite and CI
publishes its results. R01's inventory identifies the code paths each later milestone
will replace. This is the first implementation work to start.

## M1 — Versioned language, entity model and compiler

**Deliverables**

- JSON schema and semantics for game-neutral entities/characteristics/resources,
  ownership/visibility, actions, procedures, selectors, expressions and flow policies.
- Typed expression evaluator: arithmetic/rounding, comparisons, filters, aggregation,
  scoped variables and explicitly defined missing-value behavior.
- Versioned operation registry with input/output contracts, effects and preview support.
- Schema, type, reference, capability and bounded-execution validation; stable error
  codes with definition/rule IDs and JSON paths.
- Compiled immutable execution plan and deterministic definition fingerprint. Preserve
  semantic ordering; specify numbers and collection ordering rather than trusting
  incidental JSON/map iteration order.
- Published language examples and fixtures for both valid and invalid definitions.

**Automated exit gate:** valid definitions compile consistently; invalid bindings,
types, module versions, unbounded recursion and missing references fail before play.
Legitimate round cycles remain supported. Renaming a game or an action identifier does
not change semantics when references are updated consistently.

**Demonstration:** validate/compile two unrelated JSON definitions without starting
Spring or PostgreSQL. There is still no dependency on battle UI development.

## M2 — Runnable headless engine

**Deliverables**

- Interpreter for sequences, conditions, bounded loops, resource changes, effects
  and choices; explicit serializable continuations and structured outcomes.
- Definition-driven rounds, phases, alternating players/entities, budgets, passing,
  simultaneous-plan readiness and automatic transitions.
- State/event reducer, rule trace IDs and side-effect-free legality/preview queries.
- Two small complete fixtures: an alternating-turn resource race and a hidden-grid
  targeting game, each with setup, permitted actions and a terminal outcome.
- A scripted decision provider that drives sessions through the public engine contract.

**Automated exit gate:** both games complete on the same runtime using different JSON;
changing a defined phase sequence, action budget or winning threshold changes behavior
without recompilation. Illegal actions leave state/events unchanged. Serializing and
restoring at each choice produces the same final result as uninterrupted execution.

**Demonstration:** the first actual rules-driven engine running end to end, headlessly.
No game-name branching or delegation to the old battle action switch is permitted for
these fixtures. This is the early proof of architecture, not deferred to the final UI.

## M3 — Durable execution, multiplayer decisions and reactions

**Deliverables**

- Publication and session-start APIs for compiled definitions; immutable captures
  include language/module versions and the definition fingerprint.
- Persisted state, events and continuation per atomic command; actor-scoped
  idempotency keys and optimistic version checks, with defined conflict semantics.
- Registered timing windows, priority/order, pass/decline behavior and bounded nested
  reactions; no-rules-defined-response and no-eligible-responder handling.
- Seat-scoped state, legal actions, decisions, history and trace projections, including
  simultaneous private submissions. Reuse current ownership rather than duplicating it.
- An API test driver; rule explanations tied to the committed execution step.

**Automated exit gate:** the real app/database survives restart at pending decisions;
concurrent actors, retries and fault injection cannot duplicate a cost/effect/event.
Unauthorized and stale requests change nothing. Four-user checks cover owner, opponent,
spectator and admin views across every outbound route. Recovery tests use a stopped and
restarted application, not just object serialization.

**Demonstration:** two independent authenticated clients complete a game through HTTP,
including a reaction and a restart while waiting for a response.

## M4 — Dice, resources, cards and effect timing

**Deliverables**

- Numeric/symbolic dice, weighted faces, expression-derived pools, additions/removals,
  player-selected rerolls, cancellation and definition-owned result tables.
- Persisted random outcomes and transactional random-stream position; speculative
  previews and rejected actions never consume committed randomness.
- Generic token/resource ownership, capacities, conversion, ready/exhausted/spent
  states, costs, refresh and expiration. Token meaning is an executable definition.
- Effect ordering/stacking and durations; private decks/hands, draw/discard operations.
- Adapt queued orders through these contracts and define a multi-stage attack with
  post-roll attacker and defender decisions entirely in JSON.

**Automated exit gate:** exact supplied die faces produce independently specified
outcomes; changing only JSON changes dice faces and a token's timing/effect. Rerolls
cannot target ineligible dice. Nested responses, combined costs, expiry and deck
exhaustion have fixtures. Restart after a roll and retry after a failed commit cannot
produce a fresh committed outcome for the same accepted command.

**Demonstration:** the same token identifier has different behavior in two definitions,
and both follow their own timing and cost rules. No implicit forced hit or generic
defense shortcut executes on the new path.

## M5 — Spatial modules and complete headless game conformance

**Deliverables**

- Shared continuous, grid and path movement, geometry/occupancy, targeting queries
  and damage modules with declared coordinate, distance and rounding semantics.
- Definition-selected setup/roster rules, deployment, objectives, scoring and endings.
- Authoritative legal-action/preview interface and a baseline CPU decision provider
  that receives only the acting seat's projection.
- Three complete acceptance definitions: alternating-entity symbolic combat with
  orders/tokens; whole-player-phase combat with reactions; hidden-grid/no-dice play.
  Include terrain/keyword behavior only through declared supported-v1 capabilities.

**Automated exit gate:** all three play setup-to-terminal through one interpreter;
movement preview/commit agree, invalid geometry is rejected, and victory cannot be
skipped by a phase transition. Scripted and CPU runs obey the same actions and budgets.
Property/model-based tests exercise boundary positions and valid/invalid action sequences.

**Demonstration:** complete games and replay histories produced without a browser.
These synthetic games prove engine versatility; their tests do not certify every
rule of Armada, Warhammer 40k or Battleship.

## M6 — Mode resolution and catalogue migration

**Deliverables**

- Base-game/mode composition with explicit field/collection override semantics,
  cycle detection, provenance and validation of the final resolved definition.
- Captured resolved JSON plus compatible language, interpreter and module versions;
  compiler caches invalidate by content/version, not mutable catalogue identity.
- Migrate the agreed current catalogue slice from hardcoded execution to rules JSON.
  Track every R01 inventory item as migrated, legacy-only or explicitly unsupported.
- Compatibility dispatch at the session-version boundary; no fallback to legacy
  mechanics inside an interpreter-driven action. Preserve old captures or offer a
  separate restart when their runtime is unsupported.
- Document intentional rule corrections separately from parity expectations.

**Automated exit gate:** base and mode variants run without code changes; editing or
publishing a newer revision does not alter a running session. Fresh/upgrade/repeated
startup migration tests pass. Replays match recorded outcomes for supported versions;
unsupported versions fail explicitly rather than being reinterpreted as current rules.

**Demonstration:** a migrated catalogue session and two modes run on the interpreter,
with zero unclassified hardcoded rule dependencies in their declared supported scope.

## M7 — Thin generic client and release gate

**Deliverables**

- Generic UI for engine-provided legal actions, typed decisions, dice selection,
  token choices, reactions, previews and explanations. The client renders rules
  results; it does not decide eligibility, costs, distance or phase transitions.
- CPU and human clients share the same decisions and permissions; retain stateful
  browser behavior such as private drafts, selection, zoom and active drag.
- Finish automated API/browser, seeded self-play, replay, mutation, performance and
  upgrade qualification. Document authoring, supported modules and release compatibility.

**Automated exit gate:** one full-suite command passes from a clean environment and
all release checks in the test plan are green. Browser fixtures cover all three game
styles. A supported rule/mode alteration changes the available UI decisions without
editing JavaScript. No mandatory suite is optional or silently skipped.

**Demonstration:** create a definition/mode, validate/publish it, start and finish a
game, interrupt/resume it and replay it through the generic client and API.

## M8 slice — Armada setup and activation conformance

The first post-M7 slice is additive and intentionally closed in scope:

- A new catalogue pack applies `scenario.roster` to one definition-owned squadron per
  seat with explicit faction/count rules.
- Deployment is a real alternating phase. The generic client submits typed zone,
  coordinate and heading inputs; the shared spatial validator enforces ownership,
  footprint, board and collision rules.
- Completion of deployment hands into the existing alternating squadron activation
  controller. Human and CPU commands remain on the same action boundary.
- The pack documents its development-derived zero-cost boundary. It does not claim
  canonical Armada fleet legality, ship rules, upgrades, objectives or calibrated
  ruler behavior.

The conformance fixture is `ArmadaM8ConformanceTest`; the catalogue/API acceptance is
covered by `modes-catalogue.cjs`. The follow-on sourced-points slice is implemented:
see `RULES_SOURCED_POINTS.md` for the immutable two-unit cost projection, shared
preflight/setup validator, priced training pack and browser/CPU acceptance.
Player-selected roster capture is now delivered in the bounded 26-point
squadron-training pack; see `RULES_ROSTER_SELECTION.md`. Personal fleet presets with
revision-aware reuse are delivered in `RULES_FLEET_PRESETS.md`. The first catalogue
expansion is delivered in `RULES_COUNTER_SQUADRONS.md`: four sourced generic
squadrons with Counter, Swarm and Escort interactions.

The first ship slice is implemented in `RULES_SHIP_MANEUVERS.md`: two sourced small
ships, immutable maneuver/calibration capture, a generic segmented-tool module,
speed/yaw/side controls and shared preview/CPU/recovery tests. Its movement-training
format uses explicit TTS coordinates and rejects invalid final overlaps.
`RULES_SHIP_NAVIGATION.md` adds sourced command ratings, private FIFO planning,
reveal-time banking, persistent speed and atomic Navigate dial/token movement through
the existing modules. Further ship work needs official overlap resolution, combat
and explicit full-format/cap policies.

## Definition of done for this initiative

1. The declared supported-v1 game/mode behavior is expressed in captured JSON and
   interpreted through registered modules, with no hidden game-specific fallbacks.
2. Two simple games run by M2; three contrasting complete games and the migrated
   catalogue slice pass their later release gates.
3. Decisions, randomness, resources, projections and persistence behave correctly
   under retries, conflicts, interruption and replay.
4. Automated tests map each language feature, operation and milestone requirement
   to success, rejection and relevant boundary/recovery fixtures.
5. CI, deterministic repro artifacts and developer commands are committed and
   usable without manual fixture preparation.
6. All compatibility and known unsupported mechanics are documented. A future
   capability needs a new module only when the registered language/modules genuinely
   cannot express it; a new game identity alone never requires one.

## Immediate implementation queue

1. Preserve the M0–M7 regression, recovery, browser, coverage, mutation and benchmark gates.
2. Preserve hosted fast/full/mutation/performance qualification on each release.
   The baseline passed all hosted jobs at `cb6e874`; local results remain separately recorded.
3. Expand catalogue scope through explicit definitions and the H01–H22 classifications
   in `RULES_MODES_M6.md`, then pursue R21's richer authoring/inspection tools.
