# Executable game rules and language backlog

Created 2026-09-19 following the decision to make game and mode definitions
executable through a shared, JSON-based rules language.

## Goal

A definition describes how a game runs: setup, rounds, phases, actions, dice,
tokens, reactions, movement, scoring and victory. A shared interpreter executes
that definition using reusable mechanical modules. Games using supported
mechanisms can be added or changed without changes to application code.

A genuinely new mechanism may require a new reusable module. Game-specific names,
rule combinations, timing and parameters belong in definitions rather than Java
branches. The language is a bounded domain-specific language expressed in JSON,
not arbitrary Java or JavaScript embedded in configuration.

Existing sequencing, captured definitions, ownership/projection, response windows
and queued orders remain reusable foundations. M2 now supplies execution of compiled
definitions through the new headless runtime. **R01 and the M1 compiler boundary of R02–R06 are complete;
R07/R09 are delivered by M2 and the durable-command/reaction foundation of R08/R10 by
M3; R11/R12's first mechanical modules are delivered by M4; R13/R14 and the headless
CPU/cross-game boundary of R17/R20 are delivered by M5; R15 and the first scoped R19
migration are delivered by M6.** See `RULES_LANGUAGE_V1.md`, `RULES_RUNTIME_M2.md`,
`RULES_API_M3.md`, `RULES_MECHANICS_M4.md`, `RULES_SPATIAL_M5.md` and `RULES_MODES_M6.md`
for contracts, and `tools/rules-tests/README.md` for automation. Generic client/release
work continues through M7.

**Primary priority:** deliver this interpreter before further standalone battle UI
or hardcoded-mechanics development. The governing milestone plan is
[RULES_ENGINE_DEVELOPMENT_PLAN.md](RULES_ENGINE_DEVELOPMENT_PLAN.md); the required
full automated suite and tasks T01–T12 are in
[RULES_ENGINE_TEST_PLAN.md](RULES_ENGINE_TEST_PLAN.md).

## Delivery order

Follow M0–M7 in the governing plan: test infrastructure and scope; language/compiler;
headless execution; durable API/reactions; dice/resources; complete spatial games;
modes/migration; then thin client integration and release qualification. R21's
richer editor follows the engine release. The sections below are workstreams, not
a competing milestone sequence. API contracts and scripted decision providers are
delivered before the full UI portion of R16.

Tests accompany each task; R20 is the cross-game acceptance gate, not the first
testing stage. Migration and compatibility checks accompany each converted module.

Priorities: **P0** = state integrity; **P1** = executable rules foundation and usable
gameplay; **P2** = authoring and broader tooling. Dependencies below name prerequisites,
not a requirement to implement every task strictly sequentially.

## Workstream 1 — Language and definition contracts

- [x] **R01 · P1 — Inventory existing hardcoded behavior and define the migration boundary.**
  Identify phase, movement, dice, targeting, token, refresh and victory assumptions
  in the engine, UI and CPU. Classify each as definition data, interpreter behavior
  or a reusable module. **Acceptance:** an implementation map linked to F05–F22,
  with a first vertical slice and explicit treatment of existing captured sessions.
  Delivered in [RULES_ENGINE_M0_BASELINE.md](RULES_ENGINE_M0_BASELINE.md): H01–H22,
  supported-v1 scope, resource-race/hidden-grid proof and session-boundary compatibility.

- [x] **R02 · P1 — Specify rules language v1 and its JSON schema.**
  Define identifiers, references, types, procedure syntax, supported operations,
  defaults and extension/version rules. Include complete example definitions.
  **Acceptance:** a documented schema and semantics sufficient to describe a small
  game without undocumented magic names or game-name checks. Depends on R01.

- [x] **R03 · P1 — Define game-neutral entities, characteristics and resources.**
  Support players, units, cards, board objects, ownership, typed characteristics,
  zones and resource states; specify visibility and lifetime. Map existing typed
  unit fields through an explicit compatibility adapter. **Acceptance:** definitions
  can model entities without assuming hull, shields, command ratings or firing arcs.
  Depends on R02.

- [x] **R04 · P1 — Implement typed selectors, conditions and expressions.**
  Provide ownership/trait filters, comparisons, arithmetic, counts, resource checks
  and module-backed spatial queries. Define variable scope and missing-value behavior.
  **Acceptance:** deterministic evaluation with clear errors, bounded iteration and
  no access to undeclared execution context. Depends on R02/R03.

- [x] **R05 · P1 — Create a versioned operation and mechanical-module registry.**
  Declare each operation's input/output types, capabilities, effects and preview
  support. Initial operations include sequences, choices, conditions, bounded loops,
  resource changes, effects and timing windows. **Acceptance:** definitions reference
  explicit supported contracts; unsupported operations cannot be published.
  Depends on R02/R03.

- [x] **R06 · P1 — Validate and compile definitions before publication.**
  Validate syntax, types, references, module versions, expression bindings, phase
  transitions and invalid recursive procedure dependencies. Compile to a normalized
  execution plan. **Acceptance:** errors identify the rule and JSON path; invalid
  definitions fail publication rather than failing midway through play.
  Depends on R04/R05.

M1 delivery: packaged JSON Schema plus semantic validation; immutable typed plans;
signed-integer/decimal expressions, bounded selectors and pure mechanical-query
contracts; twelve core instruction contracts; exact module pins and canonical SHA-256
fingerprints. M3 adds reaction-window contracts/execution, wires the compiler into
publication, and captures the canonical definition and exact versions per session.
Unavailable modules/operations fail explicitly.

## Workstream 2 — Authoritative interpreter and game flow

- [x] **R07 · P1 — Build a resumable procedure interpreter.**
  Execute sequences, branches, bounded iteration, effects and player choices.
  Persist procedure/rule IDs, execution frames, local variables and pending decisions.
  **Acceptance:** a multi-step action resumes after application restart without
  repeating completed steps. Depends on R06.
  M2 verifies checkpoint serialization and reconstruction in fresh runtime instances;
  actual persisted application restart and transactions are R08/M3.

- [x] **R08 · P0 — Make interpreter transitions atomic and reproducible (M3 foundation).**
  Integrate optimistic concurrency, decision/action identifiers, authoritative RNG,
  transaction boundaries and event recording. Define rollback and failure semantics.
  **Acceptance:** retries and stale/duplicate responses cannot double-spend resources
  or reroll outcomes; recorded outcomes reproduce the state without fresh randomness.
  Depends on R07.
  M3 verifies transactional checkpoint/commit/receipt writes, concurrent writers,
  duplicate retries, lost acknowledgements and process-kill recovery. M4 adds durable
  random-stream state, recorded outcomes and post-roll/reroll recovery cases.

- [x] **R09 · P1 — Express setup, rounds, phases and activation policies as rules.**
  Support alternating players/entities/groups, whole-player phases, simultaneous
  planning and automatic phases. Define action budgets, required completion, passing,
  empty-phase handling and transitions. **Acceptance:** contrasting flows execute
  through the interpreter without special phase names or implicit game defaults.
  Reuse the F05 sequencing foundation. Depends on R07/R08.

- [x] **R10 · P1 — Generalize decision and reaction timing windows (M3 bounded policy).**
  Define triggers, eligible responders, priority/order, optional passes, nested
  reactions, completion and visibility. **Acceptance:** attacker modifications,
  defender responses and interrupts pause/resume the correct procedure; simultaneous
  decisions cannot reveal opponents' private choices. Reuse F06 authorization and
  projection. Depends on R04/R07/R08.
  Delivered: explicitly invoked ordered responder windows, current-state eligibility,
  optional passing, nested distinct windows, private options and one-time resumption.
  Recursive reaction cycles and distributed simultaneous windows are not part of this policy.

## Workstream 3 — Definition-driven mechanics

- [x] **R11 · P1 — Implement configurable dice and result-resolution procedures.**
  Define faces/weights, symbols, pool expressions, adding/removing dice, player-selected
  rerolls, cancellation and result tables. **Acceptance:** numeric dice and symbolic
  dice work with deterministic fixtures; a no-dice game uses the same interpreter;
  the current forced-hit behavior is absent unless a definition explicitly specifies it.
  Integrates F10. Depends on R04/R05/R10.

- [x] **R12 · P1 — Generalize tokens, cards and persistent effects (M4 modules).**
  Define ownership, capacity, creation, spending/exhaustion/discard, combinations,
  refresh, expiration and effect stacking/order. Add private decks, hands and draws
  through registered operations. **Acceptance:** token meaning and timing come from
  definitions; costs and effects resolve atomically; hidden cards and expired effects
  behave correctly. Adapt F07 and integrate F11/F17/F18. Depends on R03/R05/R10.

- [x] **R13 · P1 — Register movement, geometry, targeting and damage modules (supported M5 v1).**
  Expose continuous, grid and path movement contracts, base/zone geometry, occupancy,
  range/line-of-sight queries and damage application. Definitions select policies and
  parameters; UI and CPU use authoritative previews and validation. **Acceptance:**
  representative grid and measured-space actions run through the interpreter without
  a separate privileged CPU movement path. Integrates F08/F09/F12. Depends on R04/R05/R07.

- [x] **R14 · P1 — Define roster/setup, objectives, scoring and victory procedures (M5).**
  Connect roster constraints, initiative, deployment regions, setup choices, objective
  triggers, score updates, round limits and ties to executable definitions.
  **Acceptance:** elimination and objective-scoring examples have different setup and
  ending rules without engine identity checks. Integrates F13–F16. Depends on R09/R12/R13.

## Workstream 4 — Game modes, clients and diagnostics

- [x] **R15 · P1 — Resolve versioned game-mode inheritance and overrides (M6).**
  Define explicit merge/replacement semantics for base definitions and mode changes;
  detect inheritance cycles and unresolved references. **Acceptance:** standard,
  small-battle and team examples compile to complete definitions; the resolved JSON,
  provenance, language/module versions and checksum are captured at session creation.
  Later base/mode edits do not alter existing games. Depends on R06 and F04.

- [x] **R16 · P1 — Expose interpreter-derived actions, decisions and previews to the generic UI (M7).**
  Return legal choices, required input schemas, labels, costs, targets, visibility and
  preview results. Render selection, dice modification, token and response controls
  from these contracts. **Acceptance:** adding a supported action needs no game-specific
  page code; polling retains drafts, selection, zoom and active interactions.
  Integrates F20. Depends on R07/R10; extend alongside R11–R14.

- [x] **R17 · P1 — Route persistent CPU decisions through the same rules contracts (M7).**
  Provide a decision-provider interface consuming seat-projected state, legal choices
  and previews; use generic heuristics with optional strategy extensions.
  **Acceptance:** CPU seats complete all acceptance fixtures without hidden-information
  access or bypassing costs, timing, movement or action budgets. Integrates F19.
  Depends on R16.

  M5 delivers the projection-only baseline provider and complete headless CPU runs.
  M7 adds persistent seat scheduling, operational bounds and generic browser integration.

- [ ] **R18 · P1 — Add rule traces and actionable diagnostics.**
  Trace rule IDs, condition outcomes, resource changes, procedure steps and decisions.
  Provide rejection explanations and replay links, projected to the viewer's audience.
  **Acceptance:** a developer can explain a rejected action or changed value without
  exposing private plans, dice/deck secrets or another player's decision payloads.
  Depends on R06–R10.

  M7 delivers safe rejection explanations, observed versions, projected event/rule paths,
  visible resource deltas and authoring/resolution diagnostics. Rich condition/value
  tracing with audience-aware author tooling remains an R21 extension.

## Workstream 5 — Migration, conformance and authoring

- [x] **R19 · P1 — Migrate the first bounded catalogue slice with explicit compatibility (M6).**
  Convert existing sequencing/orders and then mechanical adapters into language-backed
  definitions. Specify supported interpreter/module versions and retirement policy;
  preserve prior captures or require an explicit separate restart when unsupported.
  **Acceptance:** each converted slice has parity/deliberate-correction fixtures;
  historical definitions and recorded outcomes are never silently rewritten.
  Plan in R01; implement alongside R09–R15.

  Delivered scope: captured generic TIE/X-wing training profiles, base and two modes;
  H01–H22 classifications and deliberate corrections are in `RULES_MODES_M6.md`.
  Broader Armada formats, ships, aces and upgrades require further incremental slices.

- [x] **R20 · P1 — Prove supported-v1 cross-game execution and replay locally end to end (M7).**
  Build three complete synthetic acceptance definitions: alternating-unit combat with
  symbolic dice/tokens; whole-player-phase combat with reactions; hidden-grid play
  with alternating cell attacks and no movement or command queues.
  **Acceptance:** all run through one interpreter, UI/API and CPU decision contract;
  tests cover invalid publication, privacy, deterministic results, restarts, concurrency,
  mode overrides and replay. Integrates F21; depends on the required R09–R19 capabilities.

  M5 delivers all three definitions through the interpreter, HTTP offers and CPU,
  with replay/restart checks. M6 adds mode/catalogue acceptance; M7 adds mandatory generic
  browser and persistent CPU recovery checks. Hosted CI execution remains pending.

- [ ] **R21 · P2 — Provide rules-authoring and inspection tools.**
  Add schema-aware editing, validation feedback, resolved-mode inspection, module
  documentation, worked examples and a dry-run/debug view. **Acceptance:** an author
  can create, validate and publish a game using supported mechanisms without editing
  Java or needing undocumented field conventions. Depends on R06/R15/R18/R20.

## Relationship to the engine correction backlog

[GAME_ENGINE_FIX_BACKLOG.md](GAME_ENGINE_FIX_BACKLOG.md) remains the record of
delivered corrections and outstanding game behavior. R-tasks describe the shared
architecture through which remaining F-tasks should be implemented, rather than
creating competing implementations or resetting delivered work.

**Next: hosted release qualification, broader classified catalogue slices and R21 authoring.**
M0–M7 implementation/local gates are delivered; see `RULES_CLIENT_M7.md`. Further client work must
use the delivered module, expression and preview contracts. Full licensed
game conformance remains a separate acceptance question from proving that the
interpreter can execute contrasting game definitions.
