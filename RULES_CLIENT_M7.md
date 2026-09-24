# M7 — Generic client, persistent CPU seats and release qualification

The supported-v1 interpreter now has a generic browser at **`/rules`**, with sessions
at **`/rules/sessions/{uuid}`**. It consumes projected workspace/action/decision APIs;
it contains no game-name dispatch, eligibility calculations, damage rules or victory
logic. The M6 catalogue's three squadron-training entries are available in the picker.

M0–M7 implementation and local qualification are delivered. **Hosted CI/release
qualification remains pending execution on the committed repository revision.**
Local results do not claim that the remote jobs have run. The broader Armada scope
remains the classified training slice in `RULES_MODES_M6.md`.

## Player workflow

1. Sign in and open **Rules Games** in the navigation.
2. Choose a published definition or catalogue mode. Catalogue scope is displayed.
3. Assign player identities to human users or CPU seats, invite viewers, and choose
   private/public visibility. The creator manages Start/Pause/Resume.
4. Start the session. Select offered activations/actions, use offered input combinations
   or edit typed fields, and answer the designated seat's decisions.
5. Preview movement, inspect visible state/mechanics and projected event history, or
   move the history slider. Return to live play to issue commands.

The browser renders integers, decimals, booleans, strings, players, entity references
and nested lists. Lists use editable JSON; declared types and offered combinations
provide the input contract. Actions and parameters are labelled from their definition
identifiers. Cards, symbolic dice, reroll indices, tokens and reaction options use the
same projected state and typed decision controls as other mechanisms.

Boards render declared public spatial bindings, shapes and zones. Zoom, panning,
selection and layer toggles are local display state. Movement proposals come from
offered inputs or explicit typed paths; the server planner supplies the validated
overlay. The browser does not decide collision, range, costs or target legality.
Public/owned resource changes from a deterministic movement preview are included as
`resources: [{entity,resource,before,after}]`. Other action previews report
`scope:"ELIGIBILITY"`; they do not predict random outcomes or hidden decisions.

## Authoring workflow

Administrators see a JSON editor on `/rules` with create/save, validate, resolved
inspection and publish controls. Unsaved edits disable operations on the saved draft.
Published documents are read-only. Full compiler/resolver diagnostics include their
JSON paths and definition/rule identifiers. Mode publication and catalogue import use
the M6 contracts; basic authoring needs no client or engine code change.

Schema autocomplete, visual procedure editing and private-value debugging remain
R21 extensions. Runtime inspection deliberately exposes safe rejection codes, observed
versions, projected values/events and visible rule paths rather than private frames,
branch traces or another seat's input. The existing server-side commit traces remain
available to trusted replay/test tooling.

## New and extended APIs

All routes require the existing authenticated user context and return `Cache-Control:
no-store`. Session access is checked before data or conditional responses are returned.

| Route | Contract |
| --- | --- |
| `GET /api/v1/rules/users` | Active users for seat/viewer assignment |
| `GET /api/v1/rules/sessions` | Up to 200 visible session summaries, newest first; no checkpoints/source |
| `GET /api/v1/rules/sessions/{id}/workspace` | One captured version's projected view, own actor choices and public board presentation |
| Existing session/catalogue create routes | Optional CPU seat kind and `cpuCommandLimit` |
| Existing state/history routes | Public seat kinds and CPU operational status alongside the projected state |
| Existing preview route | Explicit eligibility/movement scope and visible resource deltas |

Workspace ETags include session, definition/runtime, state version, paused flag and
authenticated user identity. `If-None-Match` can return 304 after an access check and
a small status query, without loading/compiling a full checkpoint. A changed response
constructs view and choices from the same checkpoint. No shared browser cache is used.

Example mixed session creation:

```json
{
  "definitionId":1,
  "visibility":"private",
  "seats":[
    {"playerId":"alice","userId":1},
    {"playerId":"bob","kind":"CPU"}
  ],
  "viewers":[3],
  "cpuCommandLimit":1000
}
```

Human seats may explicitly specify `"kind":"HUMAN"`; omitted kind preserves the
previous contract. CPU seats do not accept a client-selected `userId`. The creator is
the server-side receipt/accountability principal, **not** a controller of CPU private
state. Humans, owners and administrators cannot submit actions or previews as a CPU
seat. The `controlledPlayers` projection includes owned **human** seats only.

Persistent CPU creation requires the projection-bound runtime4 contract (compiler5
definitions with the M5 capabilities/choices). Older human captures remain supported;
they are not silently upgraded to new CPU semantics. Definitions need suitable finite
action domains for the baseline policy. Decisions already have explicit option lists.

## CPU durability and operational bounds

V40 adds immutable seat kinds and a captured CPU command limit. Existing interpreted
seats become HUMAN without changing their checkpoint or definition. Numeric legacy
sessions remain on their previous path.

`RulesCpuRunner` polls a fair queue of at most 16 sessions. Each turn claims the row
with `FOR UPDATE SKIP LOCKED`, constructs the acting seat's `RulesCpu.Input`, and sends
the chosen command through the same private implementation used by the authenticated
human command endpoint. The policy receives no checkpoint, continuation or entropy
stream. A CPU never performs Start: the owner starts the session explicitly.

- CPU command identity is deterministic per actor/checkpoint version. Checkpoint,
  commit, receipt, public `rules.cpuCommand` marker and quota increment share one
  transaction. The marker contains only the command type, not private parameters.
- Multiple workers serialize through row claims. Retry/restart reads persisted state;
  a failed/uncommitted write cannot consume a cost, decision, RNG cursor or quota.
- A pending human response is left for that human. CPU polling does not pass or answer
  it, even when the root action was initiated by a CPU.
- Limits are **1–10000 CPU commands between resumes**, default 1000. Reaching the
  limit records a public operational pause with `CPU_COMMAND_LIMIT`. Owner Resume
  preserves the game/continuation and resets the operational count.
- Missing usable domains or a rejected proposed action cause `CPU_NO_COMMAND` or
  `CPU_ACTION_REJECTED` pauses. No rule outcome or winner is invented.
- An unreadable/incompatible capture or exhausted event capacity is quarantined by
  a persisted operational flag. Its game checkpoint is preserved; no invalid game
  transition is fabricated. The session list exposes a bounded diagnostic code.

Configuration:

```text
METIS_RULES_CPU_ENABLED=true
METIS_RULES_CPU_TICK_MS=500
METIS_RULES_CPU_INITIAL_DELAY_MS=1000
```

The isolated tests use an 80ms tick. This scheduler is independent of the legacy CPU
runner and does not enable CPU control on existing human or paused legacy sessions.

## Client state and exactness

- Private input drafts are scoped by user/session/actor/action/phase/round/subject in
  session storage. Polling does not recreate an unchanged input form or overwrite
  typed values. Focus, selected entity, zoom, pan and layer preferences are preserved.
- While panning, incoming board redraws wait for the interaction to finish. Reconnect
  and reload restore the current user's local drafts/display settings.
- Numeric input and offered values preserve signed-64-bit integers and Decimal128
  tokens rather than rounding through JavaScript Number. The supported browser is a
  current Chromium-class browser with JSON source-context parsing; the suite pins its
  Playwright Chromium version. Geometry display explicitly converts to display numbers.
- Each submission stores the exact serialized body and command ID until confirmation.
  Lost/uncertain responses show **Retry command** and resend that same identity/body.
  New gameplay submissions remain blocked while confirmation is pending.
- Cached receipt views do not replace a newer workspace. Confirmed versions also
  prevent re-enabling stale controls while the next workspace response is in flight.
- History is read-only; all command handlers guard against replay mode. Background
  live updates do not replace the selected historical view. Return-to-live restores
  the latest projected workspace.
- Authentication loss removes private content. An observed account switch also
  clears the previous user's rules drafts and pending request storage. A same-account
  re-login after a server restart can recover an unconfirmed command identity.

All text/JSON is rendered using text nodes, not HTML injection. The deployment retains
the application's existing development-login model; this milestone qualifies the
rules/client contracts, not a replacement identity provider.

## Automated qualification

```text
mvn -B "-Prules-full,rules-mutation" clean verify
```

| Capability | Mandatory evidence |
| --- | --- |
| Typed UI and JSON-only variation | `rules-browser.cjs`: author/publish a base and mode, rename attack to pulse, create/play through UI |
| Exact numbers and safe rendering | Browser commits int64/34-digit decimal values exactly; markup remains text |
| Draft and interaction preservation | Opponent updates, pause/resume, active pan, focus, zoom, layers, offline reconnect and reload |
| Outbound privacy | Owner, opponent, spectator and admin workspace/HTML checks; inaccessible-session ETag denied; account-switch purge |
| Retry and replay | Browser drops an accepted HTTP response, advances another actor, retries unchanged identity, and enters read-only history |
| Complete generic games | Browser completes grid, order/dice and hidden-grid styles plus card/reroll/token decisions |
| Persistent CPU parity | `rules-cpu-integration.cjs`: all three CPU games, denied impersonation, quota/no-domain/rejection pauses and human response waiting |
| CPU recovery | Kill the process inside a blocked CPU transaction; checkpoint/receipt/events/quota roll back, then recover exactly once |
| Prior contracts | All M0–M6 SQL, compiler, module, browser, concurrency, mode, RNG and migration regressions |

The full suite also records `engine-core/target/rules-benchmark.json` using
`release-benchmark-1`: 10 warmups, 30 samples, grid-skirmish's four-piece board,
separate compile/offers/preview/submit/restore/replay timings, and a 100-command
resource-race history. Growth is capped at 500000 checkpoint bytes. These are explicit
fixture budgets, not a claim of unmeasured large-fleet throughput.

Wall-clock thresholds are versioned in `rules/benchmarks/release-v1.json`. They are
enforced by the dedicated Ubuntu 24.04 / Temurin 21 CI job, rather than flaky unit-test
deadlines. Local measurement can be run independently:

```text
mvn -B -pl engine-core -Prules-benchmark compile exec:java
```

The full profile records timings and enforces deterministic growth; the CI performance
job additionally passes `-Drules.benchmark.enforce=true`. Java/runtime coverage remains
90% line / 85% branch, and focused mutation detection remains at least 80%.

## Release boundary and next work

The executable supported-v1 game/mode path now reaches browser play and persistent
CPU execution. The migration classifications in `RULES_MODES_M6.md` still govern
broader catalogue work. Future slices should add explicit definitions/conformance for
ships, calibrated rulers, further units/keywords, objectives and upgrades. Rich authoring
and strategy improvements are separate extensions. Hosted CI on the committed revision
is the outstanding release-qualification step.

### Local delivery record — 2026-09-23

- `mvn -B "-Prules-full,rules-mutation" clean verify` passed: **323 Java tests** with
  no failures/errors/skips, plus **378** generated/exhaustive property cases.
- Language/runtime coverage gates passed; focused mutation testing detected
  **590 / 683 mutants (86%)**. All eight HTTP/browser integration suites and the
  success/failure cleanup contracts passed.
- Final full report: `target/rules-suite/metis-rules-1790187470922-319646c9/summary.json`.
- Local benchmark p95 measurements were approximately 6.01ms compile, 11.06ms offers,
  0.37ms preview, 0.15ms submit, 1.15ms restore and 0.004ms replay. The 100-command
  history used 95081 checkpoint bytes. Environment: Windows 11, Java 21.0.10, 24
  reported processors. These are local measurements, not hosted CI results.
- Deployed **V40** to the existing local database. The live `/rules` page loaded all
  three migrated training modes with zero browser errors in the read-only deployment
  smoke check.
- Session 139 remains paused at round 286, version/event count 8407; its checksum is
  unchanged: `350934b1539e9e029a09f4737f227853`.
