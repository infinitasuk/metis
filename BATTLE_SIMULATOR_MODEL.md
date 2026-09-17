# Battle Simulator Runtime Model

The simulator uses two data layers:

- `game_engine`: versioned, reusable definitions such as units, cards, rules,
  phases, actions, scenarios and fleet formats.
- `game_instance`: mutable state for one user-created simulation.

The application must not issue CRUD SQL directly against either layer. Reads
are exposed through PostgreSQL functions and mutations through PostgreSQL
procedures. REST endpoints call those routines through the repository layer.

## Runtime Entities

### Game session

The root aggregate for one simulation. It records the selected game, mode,
scenario and fleet format, a lifecycle status, current round/phase, timestamps
and extensible state JSON.

Statuses are `setup`, `running`, `paused`, `completed` and `cancelled`.

### Session player

A seat in a session. It records the display name, human/CPU kind, selected
faction, readiness and player-specific state.

### Player fleet

A player's chosen fleet format and fleet-level state. A session may eventually
support multiple fleets per player, even if the first UI supports one.

### Fleet unit

An instance of a static unit variant placed in a fleet. Runtime state includes
position, facing, activation status and extensible damage/status JSON. Static
characteristics remain in `game_engine`.

### Session board

A selected static board definition plus mutable board state such as terrain,
objective placement and deployment zones.

### Object instance

A runtime copy of a static card, token, die, dial, objective or other generic
game object. It records ownership, zone, ordering and mutable state.

### Session turn

The ordered round/phase/player turn state. This makes turn progression
explicit and supports querying the current active player.

### Session event

An append-only event log for commands and state changes. Events provide audit,
debugging and deterministic replay without relying only on the current state
JSON.

## Current API Slice

Implemented under `/api/v1`:

- `GET /sessions?gameId=&status=`
- `POST /games/{gameId}/sessions`
- `GET/PATCH/DELETE /sessions/{sessionId}`
- `GET/POST /sessions/{sessionId}/players`
- `PATCH/DELETE /players/{playerId}`
- `GET /sessions/{sessionId}/units`
- `GET /sessions/{sessionId}/events?afterSequence=`
- `POST /sessions/{sessionId}/start`
- `GET /sessions/{sessionId}/state`
- `GET /sessions/{sessionId}/legal-actions`
- `POST /sessions/{sessionId}/actions`
- `GET /definitions/{definitionId}/engine`
- `GET/POST /sessions/{sessionId}/fleets`
- `PATCH/DELETE /fleets/{fleetId}`
- `GET/POST /fleets/{fleetId}/units`
- `PATCH/DELETE /units/{unitId}`
- `GET/POST /sessions/{sessionId}/boards`
- `PATCH/DELETE /boards/{boardId}`
- `GET/POST /sessions/{sessionId}/turns`
- `PATCH/DELETE /turns/{turnId}`
- `POST /sessions/{sessionId}/events`

The simulator UI can now create a session, add players and display the runtime
fleet/board/unit/turn state. The authoritative API can validate setup, start a
session, deploy a unit, advance to movement, apply bounded movement and resolve
profile-backed attacks; accepted transitions are persisted as state JSON and
append-only events. Legal actions include only in-range attacks by ready units,
and each accepted attack resolves seeded attack dice, defense-token mitigation,
shields and catalogue-backed hull damage.
The engine core also includes deterministic CPU-vs-CPU self-play simulators for
regression testing setup, deployment, movement and turn sequencing.
The `GameEngine` interface is the infrastructure-free contract used by those
simulators and by future persistence and HTTP adapters.

## Database Routine Contract

Runtime routines currently include:

- `fn_session_list`, `fn_session_get`, `fn_session_players`,
  `fn_player_get`, `fn_session_units`, `fn_session_events`
- `sp_session_create`, `sp_session_update`, `sp_session_delete`
- `sp_player_create`, `sp_player_update`, `sp_player_delete`
- `fn_session_fleets`, `fn_fleet_units`, `fn_session_boards`,
  `fn_session_turns`, `fn_event_get`
- `sp_fleet_create`, `sp_fleet_update`, `sp_fleet_delete`
- `sp_unit_create`, `sp_unit_update`, `sp_unit_delete`
- `sp_board_create`, `sp_board_update`, `sp_board_delete`
- `sp_turn_create`, `sp_turn_update`, `sp_turn_delete`, `sp_event_append`
- `sp_engine_state_save`

Authoritative engine transitions use `sp_engine_state_save` rather than the
generic session update procedure. It checks the expected state version, writes
the next state and appends its event under one transaction.

New entities must add their read functions, mutation procedures and REST
resource together. The static Admin repository is the remaining legacy direct
treated as a fully secured production Admin surface.
