# METIS Architecture Design

**Status:** Agreed direction

METIS is designed as a game platform rather than a web application with game
logic embedded in its pages. The WebApp is one client of the GamingEngine. Other
clients, including mobile and VR interfaces, must be able to use the same
engine APIs without duplicating game rules or database access.

## Goals

- Keep the WebApp, GamingEngine and persistence concerns separate.
- Allow Web, mobile, VR and future interfaces to use the same game API.
- Keep the GamingEngine authoritative for game validity and state transitions.
- Allow Admin users to maintain game definitions through the WebApp.
- Keep game definitions versioned and prevent updates from changing existing
  simulations.
- Require application data changes to pass through versioned database routines.
- Keep the core game rules testable without Spring, PostgreSQL or a web server.

## Logical Architecture

```text
 +----------------+      +----------------+      +----------------+
 | WebApp         |      | Mobile client  |      | VR client      |
 | Simulator UI   |      | Future UI      |      | Future UI      |
 +--------+-------+      +--------+-------+      +--------+-------+
          |                       |                       |
          +-----------------------+-----------------------+
                                  |
                           HTTP / WebSocket API
                                  |
                    +-------------v--------------+
                    | GamingEngine API           |
                    | Runtime API / Admin API    |
                    +-------------+--------------+
                                  |
                    +-------------v--------------+
                    | GamingEngine                |
                    | Rules, validation, actions  |
                    | turns, AI, state changes    |
                    +-------------+--------------+
                                  |
                         Persistence adapter
                                  |
                    +-------------v--------------+
                    | PostgreSQL routine layer   |
                    | Read functions             |
                    | Write procedures           |
                    +-------------+--------------+
                                  |
                    +-------------v--------------+
                    | PostgreSQL                  |
                    | game_engine                 |
                    | game_instance               |
                    +-----------------------------+
```

The first deployment may run the WebApp and GamingEngine in the same Docker
project, but they remain separate logical boundaries. The WebApp must not
connect directly to PostgreSQL.

## Component Ownership

### WebApp

The WebApp owns presentation and user workflows:

- Simulator setup screens
- Admin screens and forms
- User identity and navigation
- Draft setup composition
- Rendering current state and legal actions
- Calling the GamingEngine API

The WebApp does not decide whether a move, attack, deployment or other game
action is legal. It must not update runtime state directly.

### GamingEngine API

The API is the stable boundary used by every client. It exposes domain
operations, not database-shaped operations.

Runtime examples:

```text
POST /api/v1/sessions
POST /api/v1/sessions/{id}/actions
GET  /api/v1/sessions/{id}/state
GET  /api/v1/sessions/{id}/legal-actions
GET  /api/v1/sessions/{id}/events
```

Admin examples:

```text
GET /api/v1/admin/games
PUT /api/v1/admin/games/{id}
GET /api/v1/admin/objects/{id}
PUT /api/v1/admin/objects/{id}
POST /api/v1/admin/definitions/{id}/publish
```

REST is suitable for commands and queries. WebSocket or Server-Sent Events may
be added for live state updates to browsers, mobile devices and VR clients.

### GamingEngine

The GamingEngine owns game truth and executable behaviour:

- Load a published game-definition version
- Validate submitted setup
- Generate legal actions
- Validate commands and reject invalid commands
- Apply movement, combat, scoring and phase transitions
- Manage turns and active players
- Run AI against legal actions
- Persist runtime state and events
- Return rule explanations and resulting state

The core rules should remain plain Java where possible. Spring, HTTP, JDBC and
PostgreSQL belong in adapters around the core.

### Database

PostgreSQL stores data but does not become a public API.

- `game_engine` stores published and draft static definitions.
- `game_instance` stores mutable simulation state, selected forces, turns,
  events and results.
- Database constraints enforce structural integrity.
- Database routines provide the persistence contract.
- Game rules and legal-action decisions remain in Java.

No Web, mobile or VR client receives database credentials.

## Admin Maintenance Flow

```text
Admin user
    |
    v
WebApp Admin UI
    |
    v
Admin API
    |
    v
Validation and authorisation
    |
    v
Database write procedure
    |
    v
Draft game definition
    |
    v
Validate and publish
    |
    v
Immutable published version
```

Admin users maintain data in the WebApp. The WebApp calls the Admin API; it
does not issue SQL or invoke database procedures directly from the browser.

The Admin API must support draft, validation and publish steps. A published
definition is immutable. Corrections create a new version rather than changing
the version already used by a simulation.

## Simulator Flow

```text
1. Client selects a game, format and scenario.
2. Client builds a draft setup.
3. Client submits the setup to the GamingEngine API.
4. GamingEngine validates the setup against one published definition version.
5. GamingEngine creates a runtime session pinned to that version.
6. Client submits domain actions through the API.
7. GamingEngine validates and applies each action.
8. GamingEngine persists state and an event.
9. Client receives the resulting state and legal next actions.
```

The initial setup may be collected entirely by the WebApp, but the engine must
revalidate it. A client cannot be trusted to submit legal fleets, positions or
state values.

## Definition Versioning

The current `game_version` metadata is a starting point but is not sufficient
by itself. Static records must be associated with a definition revision, or a
publish operation must create an immutable copy of the complete definition.

Required behaviour:

- Draft definitions can be edited.
- Drafts can be validated before publication.
- Published definitions cannot be edited in place.
- A runtime session stores the exact definition revision it uses.
- Running and completed sessions remain reproducible after later imports.
- Source revision, licence and provenance remain attached to definitions.

This is required before broad Admin editing is treated as production-safe.

## Database Routine Policy

All application data access follows this policy:

- Public clients use HTTP APIs only.
- API repositories do not issue direct table CRUD SQL.
- PostgreSQL functions are used for reads that return rows.
- PostgreSQL procedures are used for inserts, updates and deletes.
- Procedures validate cross-record ownership where practical.
- Database roles receive routine execution rights rather than unrestricted table
  write rights.
- Mutations are transactional and produce audit/event data where appropriate.

PostgreSQL functions are intentionally used for reads because they return
normal result sets. Requiring `CALL` for reads would require refcursor or OUT
parameter handling without improving the boundary.

## Consistency and Safety

The runtime API must eventually include:

- Optimistic concurrency using a session state version.
- Idempotency keys for retried commands.
- Transactional state and event writes.
- Stable action and rejection error codes.
- Append-only event history for replay and audit.
- Authentication and authorisation for Admin and player ownership.
- Rate limits and request-size limits on public APIs.

The engine should reject a stale action instead of silently overwriting newer
state.

## Deployment Direction

The initial Docker deployment may contain:

```text
metis-web       WebApp and first client UI
metis-engine    GamingEngine API and engine adapters
metis-postgres  Database and routine layer
```

The WebApp and engine can temporarily share a repository or project during the
POC, but direct WebApp-to-database access is transitional and must be removed.
The Admin API may initially live beside the GamingEngine API. If catalogue
maintenance later grows independently, it can be extracted into a dedicated
Catalogue Service without changing client contracts.

## Implementation Phases

### Phase 1: Runtime foundation

- Sessions and players
- Fleets, units and boards
- Turns and events
- Runtime REST resources
- Database routines

### Phase 2: Authoritative game loop

- Action command model
- Legal-action generation
- Movement and deployment
- State-version conflict handling
- Automatic event generation

### Phase 3: Versioned Admin catalogue

- Definition revisions
- Draft and publish lifecycle
- Admin API
- Routine-only static CRUD
- Validation and audit history

### Phase 4: Additional clients

- Separate frontend client if required
- Mobile API client
- VR state/rendering client
- Live state subscriptions

## Current Prototype Gaps

The runtime resources and procedures are now present in the prototype. The
following work remains before this design is fully enforced:

- Move the existing static catalogue reads and writes out of the WebApp's
  direct JDBC repository.
- Add immutable definition revisioning and publish routines.
- Separate the WebApp API client from the GamingEngine API client boundary.
- Implement authoritative action validation and state transitions.
- Add authentication, Admin permissions and audit controls.
- Add automated integration tests for routines and API contracts.
