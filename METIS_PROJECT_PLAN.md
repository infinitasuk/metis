# METIS Web Application Project Plan

This plan takes METIS from its current read-only catalogue to a web-based
tabletop game application. Armada is the first implementation. The product
should become useful at every stage without requiring the complete generic
engine, complete Armada rules, or computer-vision hardware up front.

## Product Direction

METIS should eventually allow a user to:

1. Choose a game and match format.
2. Build or select a legal force.
3. Start a match against an AI or another player.
4. See the board and current game state in a browser.
5. Submit actions that the rules engine validates.
6. Receive AI actions and see the resulting state.
7. Save, resume and replay the match.

The server should be authoritative for rules and state. The browser renders
state and requests actions; it must not be trusted to decide whether an action
is legal.

## Starting Point

The current application is a Spring Boot and PostgreSQL read-only catalogue
with a server-rendered web UI. The current Armada seed contains:

| Area | Current coverage |
| --- | ---: |
| Games | 1 |
| Factions | 4 |
| Unit classifications | 8 |
| Unit types | 66 |
| Unit variants | 165 |
| Characteristic types | 22 |
| Variant characteristics | 1,745 |
| Type characteristics | 0 |
| Terrain types | 4 placeholders |
| Game rules | 6 reference-only placeholders |
| Game modes | 2 reference modes with phase/scenario links |
| Source/version metadata | 1 source and 1 Armada 1.5 version |
| Generic catalogue objects | 1,476 objects across 34 object types |
| Object eligibility/associations | 591 eligibility rows and 1,299 unit-object links |
| Phases/actions/scenarios | 4 phases, 5 actions and 1 scenario |
| Boards/fleet formats | 1 board and 2 reference fleet formats |

The existing schema and catalogue backlog are documented in
`METIS_Project_Handover.md` and `DATASET_MODELLING.md`.

Static game definitions are stored in the `game_engine` PostgreSQL schema.
The `game_instance` schema is reserved for user game instances and runtime
state. The `game_engine.game_mode` table describes a static way to play the
game and must not be confused with a played game instance.

## Delivery Principles

- Build one complete vertical slice before broadening the catalogue.
- Keep static definitions separate from runtime match state.
- Keep `game_engine` and `game_instance` independently modelled and migrated.
- Keep executable rules in Java and use database data for definitions and
  parameters.
- Use a curated dataset for the POC and MVP instead of waiting for every
  Armada card and edge case.
- Preserve source revision and provenance for imported data.
- Prefer explicit typed data for values the rules engine must calculate.
- Keep free text for display, explanation and rules that are not yet
  executable.
- Do not generalise for multiple games until the Armada boundary has been
  exercised by a working match.
- Do not replace the current UI technology solely for architectural fashion.
  Decide whether Thymeleaf, a JSON API with a separate frontend, or a hybrid
  is needed based on the POC interaction requirements.

## Stage 0: Product and Technical Foundations

**Goal:** remove the decisions that would make the POC disposable.

**Rough effort:** 1-2 weeks for a focused implementation.

### Must be achieved

- Agree the first playable Armada scenario and its scope.
- Define the domain boundary between catalogue, rules, runtime and AI.
- Define the API boundary even if the first UI remains server-rendered.
- Add database migration discipline rather than relying only on init scripts.
- Add a repeatable seed/import validation command.
- Add unit and integration test conventions.
- Define an action result format for accepted actions, rejected actions and
  rule explanations.
- Define a deterministic random-number abstraction for testable combat.
- Record architecture decisions for UI, persistence, runtime state and rules.

### Data that must be modelled

- Game and game-edition identity.
- Catalogue/source package, revision, licence and import provenance.
- Stable external IDs and aliases for imported records.
- Existing game, faction, classification, type and variant relationships.
- Characteristic naming, value types, validation and inheritance semantics.
- A first distinction between definition IDs and runtime instance IDs.

### Data that can remain deferred

- Complete upgrade catalogue.
- Complete executable rules.
- User accounts and permissions.
- Multiplayer infrastructure.
- Camera observations and projection data.

### Exit criteria

- The selected POC scenario has a written rules and data scope.
- A clean database can be created and seeded from an empty environment.
- The application can load a versioned game definition.
- A sample action can be represented, validated and returned through the
  planned application boundary.

## Stage 1: Browser Vertical-Slice POC

**Goal:** prove that METIS can run one meaningful tabletop interaction in a
browser from setup to a changed state.

**Rough effort:** 3-6 weeks for a focused implementation.

### Must be achieved

- Start a new match from a selected Armada scenario.
- Select one of two curated, pre-built forces.
- Render a simple 2D board and placed units.
- Show unit profiles and current runtime values.
- Allow deployment and one or two core actions, such as movement and attack.
- Reject an illegal action with a useful explanation.
- Apply a legal action on the server and persist the resulting state.
- Have a deterministic basic AI choose a legal response.
- Refresh the browser and recover the current match state.
- Demonstrate the complete request, validation, state update and render loop.

### Data that must be modelled

- One game edition, two factions and a small curated unit set.
- Unit profile values required by the selected actions.
- Typed movement, range, firing arc, attack, defence, hull and shield data.
- Minimal terrain definitions and one board layout.
- One scenario with deployment zones and a simple victory condition.
- Runtime match, participants, chosen force and unit instances.
- Position, heading, health/shields, activation state, round and phase.
- Action requests, validation results and state-change events.
- A seeded or injectable random source for combat tests.

### Data that must not block the POC

- Every Armada unit, upgrade and keyword.
- Full fleet-building legality.
- Every scenario and objective.
- Complex timing windows and card effects.
- Authentication, payments, social features or public matchmaking.

### Exit criteria

- A new user can complete the defined vertical slice without database or
  developer tools.
- The server is the authority for at least movement and attack legality.
- Invalid actions do not change persisted state.
- The AI never submits an action rejected by the same rules boundary.
- The slice has automated domain tests and one browser/API smoke test.

## Stage 2: Constrained Armada MVP

**Goal:** deliver a small but genuinely playable web application for one human
against an AI in a defined Armada format.

**Rough effort:** 6-10 weeks after the POC, depending on the chosen rules
scope.

### Must be achieved

- Create, name, save, resume and abandon a match.
- Complete setup, deployment, activation, movement, combat and end-of-match
  flow for the selected format.
- Support rounds, phases, initiative and legal turn order.
- Support the selected victory condition and scoring.
- Show action history and enough explanation to understand rule outcomes.
- Provide a useful catalogue view for units used by the MVP.
- Provide basic AI for movement, targeting and objective behaviour.
- Handle server restart without losing completed state.
- Provide a controlled error path for stale or conflicting action requests.

The MVP should use curated fleets or a very small constrained builder. A full
fleet builder is deliberately a later milestone unless it is the primary
product objective.

### Data that must be modelled

- A versioned match format with player count, points limit, round count and
  allowed content.
- The complete unit and squadron profile semantics used by the selected
  format.
- Weapons or attack profiles, firing arcs, range bands and defence outcomes.
- Movement and manoeuvre parameters required by the selected units.
- Activation, command, token, damage and repair state.
- Scenario setup, deployment, objectives, scoring and victory conditions.
- Terrain properties used by the selected rules.
- Runtime event history or an equivalent auditable state-change record.
- Match snapshots tied to the exact game-data and rules revision used.

### Data that remains outside the MVP

- The complete upgrade-card/equipment system unless the selected scenario
  requires it.
- Every Armada scenario and optional rule.
- Multiplayer concurrency between remote users.
- Generic support for a second game.
- Physical-tabletop integration.

### Exit criteria

- A human can finish a complete supported match against the AI.
- The result can be replayed from recorded actions or state changes.
- Core rules have automated tests for legal actions, state transitions and
  victory outcomes.
- The supported data subset is imported reproducibly and has no untyped values
  where the rules engine needs calculations.
- A new release cannot silently mix incompatible catalogue and rules revisions.

## Stage 3: MVP+ Fleet Builder and Armada Content

**Goal:** move from curated forces to legal user-created Armada fleets.

**Rough effort:** 6-12 weeks, with content completeness likely taking longer
than the initial schema work.

### Must be achieved

- Browse all supported ships and squadrons.
- Add and remove units from a fleet.
- Select supported upgrades and loadouts.
- Show running points and validation errors.
- Explain why a fleet is invalid.
- Save a fleet independently from a match.
- Lock the selected fleet definition into a match snapshot.
- Support the agreed Armada factions and format restrictions.

### Data that must be modelled

- Upgrade cards, upgrade types, costs and source revisions.
- Eligibility by faction, classification, unit, slot and format.
- Unique names, duplicate limits and commander/leader restrictions.
- Fleet composition limits and alliance rules.
- Unit titles, weapons, officers, commanders and other attachable content.
- Effects that modify characteristics or grant abilities.
- A distinction between card text for display and structured effects used by
  the rules engine.
- Fleet, fleet item and loadout definitions.
- Validation rules with stable error codes and human-readable explanations.

### Exit criteria

- A user can build a legal supported fleet without hand-editing data.
- Known illegal combinations are rejected with actionable explanations.
- Imported costs, restrictions and card identities have source provenance.
- A saved fleet remains reproducible after a later catalogue revision.

## Stage 4: Armada Rules and Production Beta

**Goal:** provide a reliable, broadly useful Armada implementation rather than
only a demonstration subset.

**Rough effort:** 3-6 months depending on desired rules completeness and
content coverage.

### Must be achieved

- Implement the agreed core Armada formats and scenarios.
- Cover the supported timing windows, triggers, effects and exceptions.
- Support objectives, scoring, deployment variations and terrain rules.
- Improve AI beyond random or nearest-target behaviour.
- Add account creation, authentication and ownership of fleets and matches.
- Support multiple saved matches and safe concurrent requests.
- Add backups, migrations, structured logging, metrics and operational health
  checks.
- Add accessibility, responsive layouts and clear loading/error states.
- Add moderation or administrative controls for imported content if the system
  is opened to other users.

### Data that must be modelled

- Full supported Armada source packages and edition/version relationships.
- Structured abilities, keywords, conditions, triggers and effects.
- Objectives, objective cards and objective-zone configuration.
- Scenario setup, deployment zones, board layouts and terrain geometry.
- Full supported weapon, defence, damage, command and activation semantics.
- User, player, ownership and permission data.
- Persistent fleet versions, match snapshots, action history and results.
- Rule explanations and source references for important outcomes.
- AI difficulty profiles, evaluation parameters and tactical priorities.

### Exit criteria

- The supported rules scope is documented as complete or explicitly limited.
- Regression tests cover every supported rule family and scenario outcome.
- Two users or processes cannot corrupt the same match state.
- A match can be resumed, inspected and replayed after deployment.
- Data imports are versioned, validated and rollback-safe.
- Security and operational checks pass for the intended deployment environment.

## Stage 5: Multi-Game Platform

**Goal:** make the engine genuinely modular after Armada has exposed the correct
extension points.

**Rough effort:** an ongoing platform phase, not a prerequisite for the MVP.

### Must be achieved

- Load a second game without copying Armada-specific tables or controllers.
- Define a game package boundary for data, rules and UI capabilities.
- Allow a game to declare its board type, phases, actions, characteristics and
  content capabilities.
- Support game-specific rule implementations behind generic engine interfaces.
- Provide package validation, compatibility checks and upgrade procedures.
- Ensure the catalogue and match APIs expose generic concepts where useful but
  do not erase game-specific meaning.

### Data that must be modelled

- Game package, package version and capability metadata.
- Game-specific schema/version declarations for structured JSON values.
- Localised names, descriptions, aliases and display ordering.
- Generic definitions for units, equipment, abilities, objectives and terrain
  where a second game proves the concept is shared.
- Game-specific extension data where forcing common columns would damage the
  model.
- Rule module identity and compatibility with game/package versions.
- Import adapters, external IDs and source mappings per game.

### Exit criteria

- A second game can be imported and browsed with the generic catalogue APIs.
- Its rules can create and validate a match without Armada conditionals in the
  core engine.
- A game-specific mechanic can remain explicit without destabilising existing
  games.
- Package upgrades identify incompatible data and rule changes.

## Optional Stage 6: Physical Tabletop and Computer Vision

**Goal:** connect a physical tabletop to the same authoritative match engine.

This is not required for the web MVP or the first production release.

### Must be achieved

- Calibrate a camera and board coordinate system.
- Detect or receive observations of pieces and positions.
- Associate observations with runtime unit instances.
- Display confidence and request human correction for uncertain detections.
- Project legal AI moves or overlays back onto the physical board.
- Keep observations separate from trusted state and rules decisions.

### Data that must be modelled

- Device and camera calibration.
- Board coordinate transforms and physical dimensions.
- Observed object, candidate identity, position, heading and confidence.
- Human corrections and accepted observations.
- Projection overlays and target geometry.
- Observation timestamps and linkage to match state versions.

## Dataset Delivery Matrix

| Dataset | Foundations | POC | MVP | MVP+ | Production beta | Multi-game |
| --- | --- | --- | --- | --- | --- | --- |
| Game/package/source | Define | One revision | Versioned | Full Armada provenance | Import administration | Package registry |
| Factions and hierarchy | Stabilise | Curated subset | Supported subset | Full supported Armada | Versioned content | Generic loader |
| Characteristics | Semantics | Required typed values | Core rules values | Full profile coverage | Schemas and validation | Capability extensions |
| Unit geometry | Identify need | Simple shapes | Selected units | Complete supported units | Board-accurate geometry | Game-declared geometry |
| Upgrades/equipment | Boundary only | Defer | Minimal or defer | Full builder content | Versioned effects | Game package content |
| Fleet legality | Define boundary | Pre-built forces | Curated restrictions | Full supported formats | Explainable validation | Game-specific validators |
| Rules/actions | Interfaces | Move and attack | Core match loop | Broad Armada rules | Timing/effects completeness | Rule modules |
| Scenarios/objectives | Select one | One scenario | One supported format | Multiple scenarios | Full supported set | Package-defined scenarios |
| Terrain/board | Concept | One board | Rules-relevant terrain | Layouts and geometry | Spatial validation | Board capability model |
| Runtime state | Design split | Minimal snapshot | Full core state | Replayable history | Durable/auditable state | Generic state boundary |
| Users/permissions | Defer | Defer | Optional local identity | Design | Implement | Shared platform service |
| AI evaluation | Interface | Deterministic basic AI | Tactical baseline | Difficulty profiles | Tunable and observable | Game-specific evaluators |
| Computer vision | Defer | Defer | Defer | Defer | Optional prototype | Physical integration |

## Cross-Stage Workstreams

The stages above are product milestones. These technical workstreams run across
them:

| Workstream | POC focus | MVP focus | Later focus |
| --- | --- | --- | --- |
| Domain model | Definitions, actions and state boundary | Core transitions | Generic modules |
| Persistence | Static seed and match snapshot | Resume and event history | Scaling, backups and retention |
| Rules | Movement and attack | Full selected loop | Timing, effects and edge cases |
| AI | Deterministic legal response | Tactical baseline | Search, tuning and explanations |
| Web UI | Board and action loop | Match usability | Responsive, accessible product |
| API | Minimal action/state endpoints | Stable versioned API | External clients and integrations |
| Data pipeline | Curated fixtures | Reproducible supported import | Admin imports and package upgrades |
| Quality | Domain tests and smoke test | Regression and replay tests | Load, security and observability |

## Definition Of Done For Every Dataset

A dataset is not complete merely because rows exist. Before using it in a
release, it needs:

- A clear definition and ownership boundary.
- A stable identity and relationship to its parent definitions.
- Source, revision and licence provenance where externally sourced.
- Versioning behaviour and compatibility rules.
- Validation rules, including allowed null and free-text behaviour.
- A distinction between display data and engine-readable data.
- Import fixtures and representative Armada examples.
- Tests for normal, missing, renamed and invalid records.
- A migration or repeatable import path.
- Documentation of what remains intentionally unsupported.

## Major Risks And Controls

| Risk | Control |
| --- | --- |
| Rules complexity consumes the project | Limit each release to a written rules subset and test it before adding content |
| JSONB becomes unqueryable free text | Type and validate values needed for calculations; retain free text only for display or deferred rules |
| Upstream catalogue changes break matches | Store source revisions and snapshot the definitions used by each match |
| Generic design arrives too early | Validate abstractions against a working Armada use case and defer the second game |
| UI work hides domain gaps | Require every interaction to pass through server-side action validation |
| AI is built before legal actions exist | Implement legal action generation before tactical evaluation |
| Imported data is incomplete or inaccurate | Keep provenance, import reports and curated acceptance fixtures |
| No tests protect the rules | Add deterministic domain and replay tests before expanding the supported dataset |
| Physical tabletop scope derails the web product | Keep computer vision as an optional stage behind a stable runtime API |

## Immediate Next Milestones

1. Select the single Armada POC scenario and write its supported rules list.
2. Record the Stage 0 architecture decisions and add migration/test structure.
3. Define the minimal runtime model and action API.
4. Convert a small curated Armada subset into POC fixtures with validated
   typed characteristics.
5. Build the first browser vertical slice.
6. Review the POC before committing to full upgrades, fleet legality or a
   frontend rewrite.
