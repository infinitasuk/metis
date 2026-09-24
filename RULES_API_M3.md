# M3 — Durable interpreted sessions and reaction windows

**Delivered and locally verified, 2026-09-21.** A clean combined full/mutation run
passed 253 Java tests, 242 property checks, the coverage gates and all HTTP/browser,
rollback, lost-response and process-kill acceptance checks. Focused mutation testing
detected 206/213 mutants (97%). V36 and the API were also deployed successfully to the
local application. Session 139's paused checkpoint remained byte-equivalent by checksum.
Hosted CI is configured; its remote results are separate from these local checks.

## Delivery boundary

The new rules runtime is now available through authenticated APIs backed by PostgreSQL.
Publication, immutable captures, seat ownership, projected reads, atomic command writes,
idempotent retries, history replay, pause/resume and nested reaction windows are supported.

V36 adds separate `rules_definition`, `rules_session`, `rules_seat`, `rules_viewer`,
`rules_transition` and `rules_command` storage. Legacy numeric battle sessions continue
through their existing adapters; interpreted sessions have UUIDs and `/api/v1/rules`
routes. No new-language session is consumed by the legacy CPU runner. The browser battle
page/catalogue migration and broader generic UI remain later milestones.

Core-only captures retain `rules-compiler-3` / `rules-runtime-2`. M4 mechanical captures
use `rules-compiler-4` / `rules-runtime-3`, language v1 and exact selected module versions.
Unsupported captures return an explicit compatibility
failure, never a recapture or reinterpretation under new defaults.

## Authoring and publication

These routes require an administrator, checked both by the web boundary and service:

| Method / path | Body / result |
| --- | --- |
| `POST /api/v1/admin/rules/definitions` | Raw definition JSON; creates a draft (201) |
| `GET /api/v1/admin/rules/definitions` | Definition metadata, including drafts |
| `GET /api/v1/admin/rules/definitions/{id}` | Metadata and exact `source` JSON text |
| `PUT /api/v1/admin/rules/definitions/{id}` | `{ "expectedVersion": 0, "source": ... }`; source may be an object or exact JSON text |
| `POST /api/v1/admin/rules/definitions/{id}/validate` | Compiler validity and location-aware diagnostics |
| `POST /api/v1/admin/rules/definitions/{id}/publish` | `{ "expectedVersion": 0 }`; validates and pins executable capabilities |
| `GET /api/v1/rules/definitions` | Authenticated catalogue of published metadata; no raw source |

Drafts may contain semantic errors, but require identity/revision and fit transport
bounds. Failed publication leaves the draft unchanged. Published definitions are
immutable in both service and SQL; create another revision to change rules. Updates
and publication use optimistic edit versions. Missing versions do not default to zero.

The parser rejects duplicate keys/trailing documents and preserves integer/decimal
literal kinds and exact decimal precision. The authoring GET returns source **text**
so an editor can round-trip it without an intervening JavaScript-number conversion.

## Create and start a game

Authenticate using the existing application login. Create an interpreted session:

```http
POST /api/v1/rules/sessions
Content-Type: application/json

{
  "definitionId": 1,
  "visibility": "private",
  "seats": [
    {"playerId":"alice","userId":1},
    {"playerId":"bob","userId":2}
  ],
  "viewers": [3]
}
```

Use the returned UUID as `{id}`. The creator is the session manager. Seat order is
explicit, identities are unique, and each seat is bound to an active application user.
Multiple seats may belong to one user for hot-seat play. M3 exposes human-seat sessions;
the generic production CPU decision provider is still later work. Membership is fixed
at creation in this API slice.

Creation captures the published canonical source, compiler/runtime/module versions,
fingerprint and initial checkpoint. It does not automatically execute setup. Start as
the owner, with a fresh command identity:

```http
POST /api/v1/rules/sessions/{id}/commands
Content-Type: application/json

{"type":"start","commandId":"start-1","expectedStateVersion":0}
```

A resource-race action, using the latest returned version:

```json
{
  "type":"act","commandId":"alice-turn-1","expectedStateVersion":1,
  "actor":"alice","action":"collect","parameters":{"counter":"alice"}
}
```

Action values are converted using the compiled input types: an entity input is its
ID string, a player input is a player ID, numbers retain their declared numeric type,
and lists are recursively typed. Unknown/missing parameters and undeclared fields fail.

## Reads, previews and commands

| Method / path | Contract |
| --- | --- |
| `GET /rules/sessions/{id}` or `/state` | Projected session envelope and `RulesView` |
| `GET /rules/sessions/{id}/events` | Viewer-projected event history only |
| `GET /rules/sessions/{id}/history/{version}` | Reconstruct that committed version and project it for the caller |
| `GET /rules/sessions/{id}/actions` | Current version, owner management controls and controlled-seat candidate forms/controls |
| `POST /rules/sessions/{id}/preview` | `{actor, action, parameters, expectedStateVersion}`; no writes or receipt |
| `GET /rules/sessions/{id}/compatibility` | Version/fingerprint compatibility metadata |
| `POST /rules/sessions/{id}/commands` | Versioned, idempotent command submission |
| `DELETE /rules/sessions/{id}` | Owner-only deletion, including its runtime records |

All paths above are under `/api/v1`. Candidate forms describe typed inputs, not a
claim that all supplied values are legal; concrete previews/commands remain authoritative.

Every command requires `type`, `commandId`, `expectedStateVersion`, plus exactly:

| Type | Additional fields | Permission |
| --- | --- | --- |
| `start` | None | Session owner |
| `pause`, `resume` | None | Session owner |
| `act` | `actor`, `action`, `parameters` | Owned actor seat |
| `respond` | `actor`, `decisionId`, integer `option` | Owned designated responder seat |
| `activate` | `actor`, `entityId` | Owned active actor seat |
| `pass`, `advanceStep` | `actor` | Owned actor seat with required budgets met |

An owner or administrator without a seat cannot operate that seat. Owners can manage
the session lifecycle; administrators can author definitions and view public projections,
but receive no private-state or gameplay override. Invited spectators are read-only.
Private sessions are not discoverable to unrelated users through guessed UUIDs.

## Transaction and retry contract

M6 adds mode documents to the same authoring routes, admin `/{id}/resolved` inspection,
and `/rules/catalogue` listing/session creation backed by an explicit admin import.
Sessions capture executable resolved JSON and immutable ancestry metadata. See
`RULES_MODES_M6.md` for exact pins, override semantics, scope and compatibility.

M5 extends `/actions` with typed finite `offered` actions per owned seat and extends
`/preview` with validated movement plans. New captures use projection-bound eligibility
and deterministic movement simulation; older captured contracts remain compatible.
See [RULES_SPATIAL_M5.md](RULES_SPATIAL_M5.md) for request/response and preview limits.

Command handling locks the session row in one transaction, rechecks seat/manager
permission, checks its actor-scoped command receipt, and then evaluates the current
captured checkpoint. Reads/writes use the repository's database functions/procedures.

For an accepted transition, `sp_rules_command_save` atomically writes:

1. The checkpoint, including continuation/decision and next version.
2. Its immutable recorded commit (world/control/event delta) and paused flag.
3. The requesting user's projected response and request fingerprint.

SQL also checks that applying the recorded patch to the prior checkpoint produces the
new checkpoint, with matching session/definition/runtime identity and event sequence.
If any statement fails, all three writes roll back. Publication/capture and existing
transition/receipt records have immutable-update guards.

Receipts are keyed by **session + authenticated user + actor scope + commandId**.
Management commands use a reserved owner scope; logical seat IDs cannot collide with
it. An identical retry returns the original HTTP status/body without executing again,
even after subsequent turns or a process restart. Reusing a key with a different payload
returns `409 IDEMPOTENCY_KEY_REUSED`. Object key ordering/whitespace do not change request
identity. The expected version is part of that identity.

An authorised, well-formed rejection is also receipted without changing the state or
adding a transition. A retry of that rejection remains the same rejection; a revised
attempt needs a new key. Authentication/shape failures are not command receipts.

Responses have `{accepted, code, view}`. Accepted commands return 200, stale versions
409, and game-rule rejections 422. Cached responses retain their **original** view/version;
clients must not replace a newer displayed state with an older retry acknowledgement.
Fetch current state when needed. Unexpected storage errors return a generic 503 without
private SQL rows or parameters; a client can retry with the same identity.

## Operational pause and resume

Pause is a session-management flag, not a change to game victory or round rules. It
preserves the underlying game status, pending decision, stack and execution budget.
Pause/resume each records a versioned no-op game commit and a public lifecycle event.
Gameplay commands are denied while paused; resume restores processing eligibility.
Only the owner may perform these operations. The runtime's rules do not invent a draw
or winner to stop a stalled game.

## Definition-driven reaction windows

Language v1 now accepts an optional `windows` section and `limits.maxWindowDepth`
(default 8, maximum 16). A window declares:

- Typed `parameters` and an ordered `responders` expression returning player references.
- Explicit `maxResponders` and whether each eligible responder may pass.
- `PUBLIC` or `OWNER` decision visibility.
- Ordered choices with `id`, boolean `when`, target `procedure` and typed `args`.

Open it at the desired point in a procedure:

```json
{"op":"core.window","window":"defense","args":{"attacker":{"var":"actor"}}}
```

The window captures its bounded, unique responder order. For each responder, conditions
are evaluated against current state with an additional `responder: player` binding.
No eligible choices means an automatic skip. Otherwise the player chooses a reaction;
`$pass` is additionally offered when permitted. Each responder has one opportunity per
window traversal. This is an explicit policy, not an implicit infinite priority loop.

Selected reaction arguments come from compiled expressions, not a client replacement
action payload. The handler runs before the next responder, may itself open another
window, and does not spend the initiating action budget. On completion the original
procedure resumes exactly once. Windows, choices and handlers survive checkpoint
serialization and process restart. All unrelated commands remain blocked while waiting.

The compiler includes procedure/window dependencies and expanded costs in its checks;
recursive dependency cycles and excessive call/window depth are rejected. Runtime
checks enforce responder bounds, nesting and the existing non-renewable work budget.
Current policies support bounded nested distinct windows, not arbitrary recursive
reaction cycles. Parallel distributed decision windows remain a future extension.

See [`reaction-race.json`](engine-core/src/main/resources/rules/examples/reaction-race.json)
for paid action → private choice → defender reaction → nested acknowledgement → original
completion. M4 now adds dice and recorded RNG bookkeeping with post-roll recovery;
see `RULES_MECHANICS_M4.md` for the new operations and tests.

## Projection and recovery verification

Authoritative checkpoints, commits, continuations, traces and per-user receipt JSON
are server-side records. Public API paths return `SessionView`/`RulesView`, never those
records. State, events, history, action discovery and retries preserve owner/server
scopes. There is no new raw-state update or commit-download endpoint. Definition authors
can retrieve static source; that is separate from access to a match's private decisions.

The mandatory full-suite test `rules-api-integration.cjs` uses four independent viewer
roles plus an unrelated user and verifies:

- Draft validation/publication, exact decimal source and SQL immutability guards.
- Captures remain on the old goal after a different revision is published.
- Actor spoofing, management versus gameplay permission and read-only spectators.
- State/detail/history/event/action privacy; no frames, server values or private choice
  values leak through another player's or an unseated administrator's responses.
- Ordered nested reactions, pause/resume, original-actor budget and cost preservation.
- Two concurrent commands at one version: one commit and one stale rejection.
- Concurrent identical retries: one effect and one stored response.
- Injected failure between checkpoint and transition insertion: no partial state,
  transition or receipt; retry succeeds after removal of the fault.
- A network proxy drops the response after upstream commit; retry recovers its receipt.
- Actual application kill/restart while a nested decision is pending.
- Actual application kill while SQL is blocked after the checkpoint update but before
  commit; the uncommitted transaction rolls back and a retry applies the effect once.

Fault triggers and advisory-lock barriers exist only in the disposable test database;
there is no production fault-injection endpoint or command flag. The test reauthenticates
after restart because development HTTP login sessions are in memory; game data is durable.

Run `mvn -B "-Prules-full,rules-mutation" clean verify`. The runner installs V36 in its
fresh database, runs the established regressions, runs the new API/recovery suite and
verifies cleanup. All production migrations remain checksummed; do not edit an applied
migration. Generic UI, production CPU strategy, mode composition and legacy catalogue
conversion remain the later engine-first milestones.
