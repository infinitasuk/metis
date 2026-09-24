# Seat ownership, hidden information and response windows

F06 foundation, delivered 2026-09-19. The mechanisms are game-neutral; the current
attack adapter still has the combat simplifications tracked in F10/F11.

## Human seats and spectators

- `session_seat_owner` links logical engine players to authenticated application
  users. Human seats default to the session creator for existing hot-seat behavior.
- The setup wizard's **Controlled by** selector assigns each human seat to a user.
  Use different users for separate private views. A user deliberately controlling
  several seats can see those seats' owner-only information.
- Assignment is allowed only during setup. CPU seats have no human owner.
- Owning a seat grants access to its private session without requiring a spectator
  invitation. Viewer invitations remain read-only.
- Session owners/admins manage setup, invitations, deletion and restart. Gameplay
  requires owning the requested human seat: management/admin status is not an
  ownership override. The host may prepare setup rosters, while participants can
  also prepare their own roster through the scoped setup APIs.
- During a running battle, legacy raw state/roster/turn/event mutation endpoints
  are blocked. Actions go through the authoritative engine.
- Authenticated identity comes from the HTTP session, never a user ID in an action
  body. The project's existing passwordless development login remains a test login;
  production identity/authentication is a separate deployment concern.

API additions:

- `GET /api/v1/sessions/{id}/permissions`: controlled player IDs and management flag.
- `GET /api/v1/sessions/{id}/seats`: seat assignments.
- `POST /api/v1/sessions/{id}/seats/{playerId}` with `{"userId": ...}`: setup assignment.
- Player creation accepts optional `userId` for human seats.

HTTP action submission checks seat ownership before processing the action. Actor
zero is accepted only for shared `advance_phase`, from a seated participant, and
is bound to that participant for the event audit. Legal actions are filtered to
the requesting user's seats. Human requests cannot operate a CPU seat.

## Visibility contract

`TurnStructure.visibility` defines the disclosure policy:

```json
{
  "units": "OWNER",
  "publicValues": ["scores", "scenarioSummary"],
  "publicEvents": ["cell_fired"]
}
```

Unit audiences are `PUBLIC`, `OWNER`, or `SERVER`. This controls whole runtime unit
records, including poses, roster read APIs, and unit-related event data. The
current typed unit adapter does not independently classify each built-in unit
field; additional private attributes belong in scoped extensible state.

Extensible values can contain nested scoped data:

```json
{
  "$visibility": "OWNER",
  "ownerPlayerId": 42,
  "value": {"intent": "hold"}
}
```

`ownerPlayerId` is the logical **session-player ID**, not the application user ID.
The web boundary resolves logged-in users to those logical seats.

`SERVER` values have no client audience, including administrators. `PUBLIC` is an
explicit public envelope. Unknown scopes fail closed. Trusted modules should use
`ScopedData.owner(...)` / `ScopedData.server(...)`; event envelopes should contain
a map payload. The audience is stored with the data and preserved in history.

Unknown top-level state values and unknown event types are private by default.
The standard public protocol fields are allowed centrally, and a definition can
declare additional public values/events. Nested scopes still restrict their
contents. `serverOnly` and `initialSetupOptions` are never returned as client state.
Client setup options cannot inject response windows, private-choice state, or other
reserved engine bookkeeping.

### All outbound paths

`BattleViews` and `BattleProjectionAdvice` apply projection to:

- State reads and action-result states.
- JSON state embedded in session detail/start/restart responses.
- Separate event/history reads.
- Legacy unit/fleet/player/board/turn DTOs (legacy opaque state columns are omitted
  as `{}`; authoritative state is exposed through the projected state API).
- Both administrative engine-log routes.
- The server-rendered battle model, including inline rendering and unit markers.

Raw action parameters are no longer copied into diagnostics. Existing diagnostic
contexts/messages are not exposed to clients as a back door into older private
payloads. Database errors do not echo failing rows. Spectators and administrators
without seats receive the public projection, not the authoritative state.

Private events become `hidden_event` placeholders retaining sequence numbers,
public sequencing metadata and the viewer's own unit deltas where available.
Version increments, activity/timing, player identities and phase progression remain
public; this is content confidentiality, not traffic obfuscation. Replay receives
only the caller's projected events. Private events remain private even after a
separate, explicitly configured public reveal summary is produced.

Authenticated responses use `Cache-Control: no-store`. The battle client reloads
its view when the authenticated identity changes, rather than retaining another
login's private view. Ordinary battle updates still render in place.

## Simultaneous private phase choices

A manual phase may declare `privateInputs`:

```json
{
  "code": "planning",
  "label": "Planning",
  "mode": "MANUAL",
  "groupSize": 1,
  "steps": [],
  "privateInputs": [
    {
      "code": "intent",
      "label": "Intent",
      "choices": ["hold", "advance"],
      "required": true,
      "revealAtEnd": false
    }
  ]
}
```

`submit_private_choice` accepts `inputCode` and `value` from that input's allowed
choices, bound to the authenticated seat. Choices are stored per round/phase and
owner. A phase cannot advance until every player has supplied its required inputs.
Only fields with `revealAtEnd: true` enter the public reveal summary. Possible
options and their labels are public definition data; selected values are private.

The UI presents private choice controls only for controlled seats and preserves
unsent selections across other players' updates. Concurrent submissions retain the
existing optimistic state-version contract; stale requests must be retried.
CPUs submit their own configured choices without reading opponents' selections.

These are per-player choice primitives. F07 now adds per-entity queues, capacity,
token conversion and effects; see `ORDERS_CONTRACT.md`.

## Response windows

A definition can declare a targeted response rule:

```json
{
  "action": "attack_unit",
  "targetUnitParameter": "targetUnitId",
  "label": "Choose a defense response before resolving the attack",
  "declineParameters": {"declineDefense": true},
  "choices": [],
  "resourceField": "readyDefenseTokens",
  "resourceParameter": "defenseTokenType"
}
```

The rule is interpreted by `ResponseWindows`; a mechanical action calls its open
hook after legality validation and before executing its effect. The current
production adapter registers this hook for attacks. Other effect modules can use
the same hook; declaring a rule does not implement a missing effect module.

- The server retains the original actor and parameters under `serverOnly`.
- Public state announces the pending window and designated responder. The subject
  and available response choices are owner-visible to that responder.
- The original action has not consumed its activation budget or rolled dice yet
  in the current attack adapter.
- While a window is pending, other actions—including phase/activation completion—
  are blocked.
- `respond` requires the current `windowId` and an offered `choice` code.
- Every window permits declining. Definitions can add fixed choices or bind
  positive entries of a supported unit resource map to a mechanical parameter.
- Clients cannot replace the original actor/target through response parameters.
  Only the server's stored choice binding is used.
- Resolution resumes the original action once, applies its budget/effects once,
  and retains the original active player. The response selection is recorded with
  its owner audience. Stale/duplicate/unauthorized responses are rejected.
- A CPU handles its own response windows, using an available resource response or
  declining when none is available. Active-turn CPU strategy uses its
  projected view; it does not plan movement toward concealed enemy units. Automatic
  deployment and legality checking remain server-side operations.

**Timing boundary:** this is currently a pre-resolution response window, not the
full post-dice-roll combat sequence. The existing attack adapter still uses its
simple token mitigation. Exact dice faces, post-roll choices and token/card-specific
effects remain F10/F11. Resource bindings select parameters; their actual effects
must be implemented by the mechanical module.

## Migration and compatibility

V34 backfills existing human seats to their session creator, preserves CPU-only
ownership, and adds response configuration to the legacy catalogue's token-backed
attack profile for new captures. Existing captured definitions are unchanged.
Start a new battle (or explicitly restart) to capture that new response policy.

Restart retains seat assignments and remaps owner-scoped setup values to the new
logical player IDs. Prior private phase choices and pending responses are not
copied into the new runtime state; the source history is preserved.

## Verification

- `mvn -q test`: core scope/phase/response tests, DTO projection tests, and web
  ownership checks.
- `database/src/test/sql/seat_ownership.sql`: defaults, CPU exclusion, participant
  viewing and locked running assignments; rolled-back fixtures.
- `webapp/src/test/js/multiplayer-integration.cjs`: independent Alice, Bob,
  spectator and admin sessions; state/detail/events/HTML/log/roster projection;
  actor spoofing; private readiness; restart ownership; stale/duplicate responses;
  CPU responses. Uses built-in Node APIs.

Run integration checks in an isolated database/app after building `docker-app`:

```powershell
docker run --rm -d --name metis-f06-db --network docker_default -e POSTGRES_USER=metis -e POSTGRES_PASSWORD=integration-test-only -e POSTGRES_DB=metis_test -v "${PWD}/docker/init/sql:/docker-entrypoint-initdb.d:ro" postgres:16
# Wait for PostgreSQL bootstrap to complete, then start the test app.
docker run --rm -d --name metis-f06-app --network docker_default -p "127.0.0.1:18081:8080" -e "DB_URL=jdbc:postgresql://metis-f06-db:5432/metis_test?escapeSyntaxCallMode=callIfNoReturn" -e DB_USER=metis -e DB_PASSWORD=integration-test-only docker-app
# Wait for Flyway V35 and application startup before seeding the fixture catalogue.
docker cp database/src/test/sql/multiplayer_fixtures.sql metis-f06-db:/tmp/multiplayer_fixtures.sql
docker exec metis-f06-db psql -U metis -d metis_test -v ON_ERROR_STOP=1 -f /tmp/multiplayer_fixtures.sql
node webapp/src/test/js/multiplayer-integration.cjs
docker stop metis-f06-app metis-f06-db
```

The Node script deletes its sessions. Stopping the `--rm` containers removes the
isolated fixture database. `TEST_BASE_URL`, `TEST_DB_CONTAINER`, and `TEST_DB_NAME`
can select another explicitly prepared test environment.
