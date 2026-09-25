# METIS Implementation Plan

**Status:** Engine-first delivery; revised 2026-09-19.

M0–M7 are implemented with local verification: compiler/runtime, durable APIs, reactions,
mechanics/spatial modules, pinned modes, catalogue migration, generic browser and persistent CPU seats. Recovery, coverage
and mutation gates pass. Hosted CI awaits repository execution.
The first M8 setup slice is now implemented locally as an additive catalogue pack; see
`RULES_M8_ARMADA_SLICE.md`. Hosted CI awaits repository execution.
**Next release step: qualify and deploy the M8 revision.**

The primary priority is an actual rules-driven engine: executable game/mode JSON,
a shared resumable interpreter and reusable mechanical modules, backed by a full
automated test suite. Standalone battle UI development and additional hardcoded
game behavior are deferred while this architecture is delivered.

## Active delivery plan

[RULES_ENGINE_DEVELOPMENT_PLAN.md](RULES_ENGINE_DEVELOPMENT_PLAN.md) is the governing
milestone sequence. [RULES_ENGINE_TEST_PLAN.md](RULES_ENGINE_TEST_PLAN.md) defines
required automation and exit gates; [RULES_LANGUAGE_BACKLOG.md](RULES_LANGUAGE_BACKLOG.md)
contains the detailed implementation tasks.

| Milestone | Required outcome |
| --- | --- |
| M0 | Hardcoding inventory, v1 scope, reproducible test harness and CI |
| M1 | Versioned JSON language, typed expressions, operation registry and compiler |
| M2 | Headless interpreter completing two different JSON-defined games |
| M3 | Durable API sessions, authorized decisions/reactions, atomic retry and recovery |
| M4 | Definition-driven dice, tokens, cards and timed effects |
| M5 | Spatial modules, setup/scoring and three complete headless acceptance games |
| M6 | Mode resolution, immutable version captures and catalogue migration |
| M7 | Thin generic UI/CPU integration and full automated release qualification |
| M8 slice | Definition-owned roster policy, legal deployment and activation handoff for a closed Armada squadron training pack |

Tests are delivered with every milestone, beginning in M0. Existing-session
maintenance remains possible, but new UI work serves interpreter contracts after
the headless engine is proven. The earlier Phase 0–8 roadmap below is retained as
reference; it does not override M0–M7 or authorize parallel hardcoded battle work.

## Guiding Decisions

- Keep one Git repository with independently buildable components.
- Treat the WebApp as a client of the GamingEngine API.
- Keep the GamingEngine authoritative for setup validation, legal actions and
  state transitions.
- Keep static definitions in `game_engine` and mutable matches in
  `game_instance`.
- Expose HTTP APIs to clients; never expose database credentials.
- Use PostgreSQL functions for reads and procedures for inserts, updates and
  deletes.
- Keep the interpreter and reusable mechanical primitives in plain Java; store
  executable game/mode rules as versioned JSON definitions in PostgreSQL.
- Pin every simulation to an immutable game-definition revision.
- Deliver corrections through reusable rule modules and versioned definition data.
  Verify contrasting activation/board models before claiming a module is game-neutral.

The current behavior-correction queue is in
[`GAME_ENGINE_FIX_BACKLOG.md`](GAME_ENGINE_FIX_BACKLOG.md), following the full
rules review. Its remaining mechanics are delivered through the rules-engine plan,
which supersedes game-specific implementation assumptions below.

## Current Baseline

Completed foundations:

- Initial Git repository on `main`.
- Static Armada catalogue and import scripts.
- `game_engine` and `game_instance` schemas.
- Runtime tables for sessions, players, fleets, units, boards, turns and events.
- REST resources for the runtime foundation.
- Simulator/Admin WebApp areas.
- Stored routines for runtime CRUD.
- Version metadata for engine builds, API contracts, event schemas and game
  definition revisions.
- Definition lifecycle metadata and Admin API operations for draft, published
  and retired revisions.
- Architecture and runtime model documentation.
- Framework-independent engine setup and movement state transitions with plain
  Java tests.
- API setup/start/action boundary with persisted state and event generation.
- Legal-action discovery and active-player turn sequencing for the movement slice.
- Atomic state-version checks for engine state and event persistence.
- Deterministic CPU-vs-CPU self-play regression simulations across multiple seeds.
- Plain-Java `GameEngine` contract test covering setup validation and initial state creation.
- Definition-backed attack legality, hull damage and destroyed-unit state transitions.
- Captured session definitions, configurable activation sequencing, seat ownership,
  private projection, response windows and queued-order foundations (F04–F07).

Known gaps:

- WebApp still contains legacy direct JDBC access to static catalogue data.
- The WebApp and GamingEngine are not yet separate runtime services.
- WebApp catalogue pages support selected revisions, but remaining Admin content
  mutations are not all revision-aware yet.
- Base game metadata and global characteristic types are not yet revision-owned.
- Build SHA injection is available for Docker deployments but is not yet part of
  an automated release pipeline.
- Remaining catalogue mutation coverage is not fully revision-aware for all
  relationship and unit-definition records.
- The engine does not yet load complete Armada rules or generate all legal
  actions; the current combat slice is intentionally a minimal attack model.
- The interpreted runtime now has its own persisted/authenticated API path and M4
  mechanics. Spatial modules, mode composition, catalogue migration and generic UI
  remain later milestones; the existing battle page still uses its captured legacy rules.

## Phase 0: Architecture And Delivery Foundation

**Goal:** make the component boundaries real without changing the product
behaviour.

Work:

- Split code into logical modules:
  - `engine-core`
  - `engine-api`
  - `webapp`
  - `database`
- Define the OpenAPI contract for runtime and Admin APIs.
- Make the WebApp call the GamingEngine API instead of accessing runtime or
  static tables directly.
- Introduce Flyway or an equivalent migration history.
- Add separate Docker build targets for WebApp and GamingEngine.
- Add CI for compile, database setup and API smoke tests.
- Define API error codes and response conventions.

Exit criteria:

- WebApp has no direct database connection.
- GamingEngine API starts independently.
- A clean environment can build and start both services.
- API contracts are committed and tested.

## Phase 1: Definition Lifecycle And Admin API

**Goal:** allow safe game-data maintenance without changing existing matches.

Work:

- Add definition revision identity and lifecycle:
  - `draft`
  - `published`
  - `retired`
- Decide whether revisions are represented by versioned rows or immutable
  catalogue copies.
- Associate all engine records with a definition revision.
- Add stored procedures for static catalogue CRUD.
- Replace direct static JDBC queries in the current repository with routine
  calls.
- Add Admin API resources for games, modes, scenarios, boards, units, objects
  and rules.
- Add validation and publish procedures.
- Add change history and provenance tracking.
- Add Admin authentication and role checks before production use.

Exit criteria:

- An Admin can edit a draft definition through the WebApp.
- Invalid definitions cannot be published.
- Published definitions cannot be edited in place.
- Existing simulations continue to use their original revision.
- Every Admin mutation is auditable.

## Phase 2: Engine Core And Runtime Boundary

**Goal:** create a framework-independent authoritative game engine.

Work:

- Create plain Java domain types for:
  - `GameDefinition`
  - `GameSetup`
  - `GameState`
  - `Player`
  - `UnitState`
  - `BoardState`
  - `Action`
  - `ActionResult`
  - `GameEvent`
- Add ports for definition loading and runtime persistence.
- Implement session creation from a published definition revision.
- Validate player count, factions, formats, scenarios and starting state.
- Add state versioning for optimistic concurrency.
- Add deterministic randomness abstraction.
- Add stable rejection codes and rule explanations.

Current implementation includes immutable core setup/player/unit/board/event
types, setup validation, seeded randomness, deployment, phase advancement and
bounded movement transitions. The API start/action endpoints are the first
adapter around this core.

Exit criteria:

- Core rules can run in a plain Java unit test without Spring or PostgreSQL.
- A valid setup produces a deterministic initial state.
- An invalid setup is rejected before persistence.
- State changes and events are written transactionally.

## Phase 3: First Playable Armada Slice

**Goal:** prove the complete request-to-state-change loop with a small scope.

Initial scope:

- One Armada game edition.
- One standard scenario.
- Two factions.
- Curated forces.
- Deployment.
- Movement.
- Round and phase progression.
- Basic victory condition or fixed demonstration end state.

Work:

- Add deployment-zone validation.
- Add board coordinate and unit geometry model.
- Generate legal deployment actions.
- Generate legal movement actions.
- Validate movement distance, heading, overlap and board boundaries.
- Apply movement state transitions.
- Record movement events.
- Return updated state and legal next actions.
- Add API endpoints:

```text
POST /api/v1/sessions
GET  /api/v1/sessions/{id}/state
GET  /api/v1/sessions/{id}/legal-actions
POST /api/v1/sessions/{id}/actions
GET  /api/v1/sessions/{id}/events
```

Exit criteria:

- A user can create a supported match without developer tools.
- A unit can be deployed and moved through the WebApp.
- Illegal movement is rejected with an explanation.
- Legal movement persists after a browser refresh.
- The event log can reconstruct the movement sequence.

## Phase 4: Simulator WebApp

**Goal:** provide a useful interface over the authoritative engine.

Work:

- Replace setup-only controls with API-backed setup.
- Add curated fleet selection.
- Add board rendering.
- Add selectable units and legal-destination display.
- Add action confirmation and error states.
- Add current phase, round and active-player indicators.
- Add event/message history.
- Add responsive layout for desktop and tablet.
- Keep rendering logic independent of game-rule decisions.

Exit criteria:

- The complete movement slice works through the WebApp only.
- The same match can be viewed from another API client.
- The WebApp contains no game-rule implementation.

## Phase 5: Combat And Turn Processing

**Goal:** turn the movement demo into a basic playable battle.

Work:

- Add action sequencing and active-player validation.
- Add activation state.
- Add range and firing-arc calculations.
- Add attack profiles and defence results.
- Add dice/randomness outcomes.
- Add hull, shield, damage and destroyed state.
- Add combat event types.
- Add scoring and victory checks.
- Add replay from events or action history.

Exit criteria:

- A human can complete a small supported battle.
- All state transitions are validated by the engine.
- Combat results are deterministic when supplied with a test seed.
- A completed battle can be replayed.

## Phase 6: Basic AI

**Goal:** allow one human to play against an engine-controlled opponent.

Work:

- Generate legal actions through the same engine API used by humans.
- Add deterministic baseline AI.
- Evaluate movement, attack opportunities, objectives and survival.
- Add configurable difficulty parameters.
- Record AI decisions and outcomes.
- Ensure AI cannot submit an action rejected by the engine.

Exit criteria:

- A human can complete the supported battle against AI.
- AI behaviour is reproducible under a fixed seed.
- AI tests use the same legal-action boundary as player actions.

## Phase 7: Persistence, Accounts And Operations

**Goal:** make the simulator safe and resumable for real users.

Work:

- Add user accounts and ownership.
- Add Admin roles and permissions.
- Add save, resume, abandon and delete workflows.
- Add idempotency keys for commands.
- Add stale-state conflict handling.
- Add structured logging and metrics.
- Add database backup and restore procedures.
- Add API rate and request-size limits.
- Add security and dependency scanning.

Exit criteria:

- Users can safely manage multiple matches.
- Concurrent or retried commands cannot corrupt state.
- Admin changes and game actions have traceable audit records.

## Phase 8: Additional Clients

**Goal:** support mobile and VR without changing engine rules.

Work:

- Generate client models from the versioned API contract.
- Add live state subscriptions using WebSocket or Server-Sent Events.
- Define a compact state representation for mobile.
- Define board, unit and interaction capabilities for VR.
- Add client-specific presentation adapters without client-specific rules.
- Add compatibility testing across Web, mobile and VR clients.

Exit criteria:

- A second client can create, observe and act in a match using the same API.
- No game logic is duplicated in the second client.
- API version compatibility is documented and tested.

## Testing Strategy

The full strategy and implementation tasks T01–T12 are specified in
[`RULES_ENGINE_TEST_PLAN.md`](RULES_ENGINE_TEST_PLAN.md). M0 establishes CI and
isolated orchestration; every later milestone adds its required scenarios. The
full command is now implemented; the extended profile and later feature tests remain
planned deliverables. See `tools/rules-tests/README.md` for prerequisites and artifacts.

Required automated coverage includes:

- Database routine integration tests.
- Engine domain unit tests.
- Language/schema/compiler validation and operation-contract tests.
- Property/model-based tests, independent scenario oracles and JSON-only game variation.
- API contract tests.
- Persistence and transaction tests.
- Concurrent submission, idempotency, interrupted execution and crash-recovery tests.
- Seat projection, hidden-information and response authorization tests.
- Replay tests.
- WebApp smoke tests.
- Headless cross-game self-play and mandatory generic-client browser acceptance.
- Mutation, architectural, performance and migration/compatibility checks.
- Cross-client compatibility tests once additional clients exist.

Rules tests must cover accepted actions, rejected actions, state changes,
events, randomness and boundary conditions.

## Definition Of Done

A feature is complete only when:

- Its data model is documented.
- Its API contract exists.
- Its reads use database functions.
- Its writes use database procedures.
- Its engine behaviour is covered by independent success, rejection and boundary
  fixtures, plus recovery/privacy cases where applicable.
- Its state changes are auditable where applicable.
- Where a UI surface is delivered, it handles loading, success, rejection and failure
  states using interpreter-derived contracts rather than duplicated rules.
- Its migration works on clean and supported existing databases.
- Its compatibility with existing definition revisions is understood.
- The milestone's required suite and regression jobs pass in CI without silently
  skipping tests or requiring manual fixture preparation.

## Immediate Work Queue

1. Run hosted fast/full/mutation/performance CI on the committed revision.
2. Preserve the local browser/CPU, recovery, privacy and replay acceptance gates.
3. Expand catalogue scope through classified rule migrations and richer R21 authoring tools.
4. Add canonical Armada costs and roster legality before broadening the M8 pack beyond its closed training scope.

M0–M8 implementation/local gates are delivered; hosted release qualification remains pending. Preserve the
combined command: `mvn -B "-Prules-full,rules-mutation" clean verify`.
