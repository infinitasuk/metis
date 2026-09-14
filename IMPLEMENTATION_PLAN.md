# METIS Implementation Plan

**Status:** Active

This plan turns the agreed architecture into an implementation sequence. It is
deliberately focused on the first playable battle simulator and the boundaries
needed for future mobile and VR clients.

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
- Keep game rules in plain Java and definition data in PostgreSQL.
- Pin every simulation to an immutable game-definition revision.
- Deliver one complete Armada vertical slice before broadening the rules or
  supporting another game.

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

Known gaps:

- WebApp still contains legacy direct JDBC access to static catalogue data.
- The WebApp and GamingEngine are not yet separate runtime services.
- Static definitions are not fully revisioned or publishable.
- Build SHA injection is available for Docker deployments but is not yet part of
  an automated release pipeline.
- The engine does not yet validate or execute game actions.
- No automated tests currently exist in the repository.

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

Every phase should add tests at the appropriate boundary:

- Database routine integration tests.
- Engine domain unit tests.
- API contract tests.
- Persistence and transaction tests.
- Replay tests.
- WebApp smoke tests.
- Cross-client compatibility tests once additional clients exist.

Rules tests must cover accepted actions, rejected actions, state changes,
events, randomness and boundary conditions.

## Definition Of Done

A feature is complete only when:

- Its data model is documented.
- Its API contract exists.
- Its reads use database functions.
- Its writes use database procedures.
- Its engine behaviour is covered by tests.
- Its state changes are auditable where applicable.
- Its UI handles loading, success, rejection and failure states.
- Its migration works on a clean database.
- Its compatibility with existing definition revisions is understood.

## Immediate Work Queue

1. Introduce component/module boundaries for `engine-core`, `engine-api` and
   `webapp`.
2. Define the first Armada movement scenario and curated forces.
3. Add definition revision and publish modelling.
4. Replace static WebApp JDBC access with Admin/API routine access.
5. Implement the first authoritative action: deployment or movement.
6. Add the board state and legal-action API.
7. Connect the WebApp board to that API.
