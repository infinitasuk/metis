# Game-neutral engine correction backlog

Created 2026-09-19 from [ARMADA_RULES_REVIEW.md](ARMADA_RULES_REVIEW.md).

## Design requirements

- Keep the core free of game-name checks and game-specific unit/card names.
- Store source-stat bindings, supported mechanics, phase sequences, action budgets,
  geometry, visibility, and victory configuration in immutable definition revisions.
- Use reusable, tested rule modules for complex mechanics. Configuration selects
  modules and parameters; it does not require every rule to become a generic script.
- Clients and CPU agents consume the same legal-action/preview contracts.
- Support different turn structures: alternating entities/groups, whole-player
  phases, simultaneous planning, and alternating single actions.
- Support different board models: measured continuous space and addressed grids.
- Separate tentative placement/movement from committed effects, random outcomes,
  revealed information, and completed activations.
- A new game must explicitly configure its mechanics. It must not silently inherit
  command stacks, firing arcs, maneuver charts, or other legacy defaults.
- Existing typed fleet fields are a compatibility adapter, not the final universal
  unit model. The data-integrity work below does not claim 40k or Battleship gameplay
  is already implemented.

## Primary delivery priority — rules-driven engine

The main development priority is a versioned, JSON-based rules language with a
resumable interpreter and reusable mechanical modules. Game and mode definitions
will specify flow, dice, tokens, decisions and effects rather than relying on
game-specific execution paths.

Follow [RULES_ENGINE_DEVELOPMENT_PLAN.md](RULES_ENGINE_DEVELOPMENT_PLAN.md), milestones
M0–M7, with the full automated suite in
[RULES_ENGINE_TEST_PLAN.md](RULES_ENGINE_TEST_PLAN.md). Standalone UI enhancements
and additional hardcoded battle behavior are deferred. Remaining F-tasks are behavior
requirements to implement through that engine, not a parallel legacy feature track.

See [RULES_LANGUAGE_BACKLOG.md](RULES_LANGUAGE_BACKLOG.md) for tasks R01–R21,
priorities, dependencies and acceptance criteria. **M0 is implemented and locally
verified:** see `RULES_ENGINE_M0_BASELINE.md` and `tools/rules-tests/README.md`.
**M1's language/compiler contracts are also delivered:** see `RULES_LANGUAGE_V1.md`.
**M2's headless runtime is delivered:** see `RULES_RUNTIME_M2.md`.
**M3's durable APIs and reaction windows are delivered:** see `RULES_API_M3.md`.
**M4's mechanics and durable randomness are delivered:** see `RULES_MECHANICS_M4.md`.
**M5's spatial/setup/scoring and headless CPU contracts are delivered:** see `RULES_SPATIAL_M5.md`.
**M6's mode resolver and bounded catalogue migration are delivered:** see `RULES_MODES_M6.md`.
**M7's generic client and persistent CPU path are implemented:** see `RULES_CLIENT_M7.md`.
**M8's first setup slice is implemented:** see `RULES_M8_ARMADA_SLICE.md`. Hosted release CI and broader
classified catalogue work are next. Remaining F08–F22 work should build on these
contracts. Existing delivered foundations and their statuses below are retained.

## Prioritized fixes

| ID | Priority | Fix | Reusable design / acceptance criteria | Status |
| --- | --- | --- | --- | --- |
| F01 | P0 | Correct unit-profile projection and missing command/token fields | Revision-owned source bindings; health aliases with explicit precedence; no silent required-health fallback; golden data assertions | Delivered |
| F02 | P0 | Track and automatically apply database upgrades | Checksummed migrations on both fresh and existing databases; repeat startup applies no duplicate changes; deployment errors stop startup | Delivered |
| F03 | P0 | Enforce active-player ownership for attacks | Shared action-authorization guard used by move, attack, reset and end-turn; invalid actions never change state or consume randomness | Delivered |
| F04 | P0 | Protect snapshot/definition consistency | Versioned profile/rules contracts, explicit old-state migration/restart policy; no automatic healing or rewriting past battles | Delivered: captured definitions + explicit restart |
| F05 | P1 | Replace hardcoded phases and player-wide activation | Definition-selected phase/activation policies; active entity/group, step, action budgets, mandatory passes and phase completion | Core slice delivered |
| F06 | P1 | Implement player-controlled hidden information | Seat ownership, per-player state/event projection, simultaneous submissions, response windows | Foundation delivered |
| F07 | P1 | Implement command/planning mechanics | Optional queued-order module, capacity per entity, reveal timing, resource conversion and effect resolution | Foundation delivered |
| F08 | P1 | Unify direct manipulation and CPU movement | Definition-selected continuous/grid/path movement; origin-based previews; legal facing; one commit boundary; shared validators | Planned |
| F09 | P1 | Correct geometry and overlaps | Base shapes, zones, targeting points, destination occupancy, collision resolution policies, board-edge consequences | Planned |
| F10 | P1 | Replace simplified attack resolution | Attack-step state machine, selectable sources/targets, attack budgets, configurable dice faces/pools and modifiers; remove automatic-hit rule from normal combat | Planned |
| F11 | P1 | Implement defensive responses and damage effects | Defender input, reusable resource/token effects, spend/exhaust/discard lifecycle, optional damage-deck and persistent-effect modules | Planned |
| F12 | P1 | Separate unit-class activation/movement behavior | Traits select eligibility, movement measure, engagement rules and action budgets; no hardcoded ship/squadron split in shared engine | Planned |
| F13 | P1 | Use canonical costs and common roster validation | Definition-owned costs/provenance, uniqueness, slot/cap/faction constraints; validate presets, CPU and live rosters at start | Planned |
| F14 | P1 | Implement initiative, legal deployment and setup choices | Configurable setup workflow, initiative policy, deployment regions, group placement, initial-resource choices | Planned |
| F15 | P1 | Implement scoring and victory | Pluggable elimination/scoring/round-limit/tie policies; support scored battles and grid target elimination | Planned |
| F16 | P2 | Replace decorative terrain/objectives with runtime objects | Geometry, placement rules, interactions, triggers and scoring; UI renders state rather than fixed shapes | Planned |
| F17 | P2 | Implement upgrades, keywords and named effects | Optional loadouts, eligibility, reusable effect/timing hooks, executable support registry per revision | Planned |
| F18 | P2 | Complete refresh/end-of-round behavior | Definition-driven resource refresh, effect expiration, order cleanup, recurring/paid refresh, objective timing | Planned |
| F19 | P2 | Adapt CPU to the selected mechanics | Budget-legal roster generation and activation-aware actions through shared interfaces; no privileged movement path | Planned |
| F20 | P2 | Correct labels and legal-action UI | Labels from definitions; distinguish current/min/max resources; selected-actor legal targets; previews from rules; preserve view settings | Planned |
| F21 | P2 | Strengthen conformance and replay tests | Rules examples, real projection checks, negative authorization tests, deterministic outcomes, browser interaction checks | Planned |
| F22 | P3 | Support specialist and campaign formats | Optional extra zones, reaction attacks, delayed deployment, campaign persistence, teams and tournament policy modules | Planned |

P0 = data integrity / invalid state transitions. P1 = core playable rules.
P2 = complete standard gameplay and consistent clients. P3 = expanded formats.

### Live-session regression: unlimited CPU stalemate (session 139)

Observed 2026-09-20 in the captured `activation-v1` simulator, mode **Unlimited
Deathmatch**. The round limit was correctly disabled, but the UI incorrectly showed
`/ 6`. The last damage occurred in round 124; subsequent rounds repeated blocked
movement/holding position and single-hit attacks fully cancelled by refreshed defense
tokens. This is a reproducible legacy movement/combat/CPU interaction, not a stalled
pending human input or a failure of the new headless runtime.

At the user's request, session 139 was paused at round 286, state version 8407 using
the version-checked state-save routine. Prior events, units, options and pending state
were retained; one pause event was appended. Unlimited-round and paused-status displays
were corrected. The unlimited mode's rules and battle outcome were not rewritten.

Carry this case into F08/F10/F11/F19/F21 and the corresponding rules-driven modules:
verify blocked movement decisions, defense/resource effects and observable no-progress
behavior. Unlimited modes need an explicit rule or operational intervention for a
stalemate; do not invent a winner or silently impose the standard six-round limit.

## First delivery batch

F01–F03 delivered with automated data/engine checks. Existing runtime snapshots are
preserved; F04 now supplies an explicit restart into a new session rather than
guessing historical damage or changing old resources.

Verification completed:

- `mvn -q test`: passed, including phase-name-independent ownership and out-of-turn
  attack rejection before random-number consumption.
- `database/src/test/sql/profile_mapping.sql`: passed on the upgraded local database
  and a separately bootstrapped clean database; fixtures rolled back.
- Fresh bootstrap plus Flyway upgrade to V32: application started successfully.
- Existing database baseline plus V32: applied successfully; second application
  startup validated checksums and reported no migration necessary.
- Live catalogue samples: CR90 health 4 and command 1; Cymoon health 11 and command
  3; named defense-token counts restored. All 59 published ship profiles now have
  health values in the imported range 3–14 instead of all being 1.
- Profile-mapping Admin API, fleet setup page and existing battle page: HTTP 200.

Implementation locations: `ActionAuthorization`, versioned database migration
`V32__definition_profile_mapping.sql`, Admin definition profile-mapping endpoints,
and the packaged database module. These are reusable infrastructure and a
compatibility projection. The subsequent F04–F05 slice is described below;
movement, combat and the remaining rule effects still require their own milestones.

## Activation milestone (F04–F05)

Implemented and verified; see [ACTIVATION_AND_SNAPSHOT_CONTRACT.md](ACTIVATION_AND_SNAPSHOT_CONTRACT.md)
for the snapshot policy, API, configuration format, supported modes and remaining
mechanical limits. Legacy unpinned battles are historical/read-only and can be
restarted into separate sessions. Captured battles use their own immutable profiles
and sequencing configuration. The CPU and UI consume the same sequencing rules.

F06's ownership, projection, private-choice and response-window foundation is now
delivered, followed by F07 command queues and reusable planning effects. F08–F12 cover the
existing movement/combat simplifications.

### Acceptance fixtures

The delivered `TurnStructure` and `ActivationContext` track active entities/groups,
steps and action budgets around an action-effect callback. Tests exercise three
contrasting synthetic definitions:

1. Alternating entity activations with ordered actions and a path maneuver.
2. Whole-player phases with per-entity movement, ranged attacks and reactions.
3. Hidden grid placements and alternating single-cell attacks with no movement,
   command queue, defense tokens or firing arcs.

These are sequencing acceptance fixtures, not claims of complete licensed rulesets.
The current battle profile is connected through definition data and the existing
mechanical adapter. The UI now distinguishes Finish activation from End phase.
Pending response state is now implemented by F06. A broader production rule-module
registry and full effect timing remain future work.

## Multiplayer milestone (F06)

See [MULTIPLAYER_CONTRACT.md](MULTIPLAYER_CONTRACT.md) for the audience contract,
API routes, setup ownership, response policy and integration test instructions.

Delivered and verified:

- Authenticated human-seat ownership, setup assignment UI/API and participant
  access to private sessions; spectators are read-only and humans cannot act for CPUs.
- No gameplay/private-view override from session management or admin roles.
- Owner/server-scoped values and hidden-unit policies; projected state, action
  results, session JSON, raw roster DTOs, events, server HTML, replay and diagnostics.
- Definition-declared private phase choices and required-player readiness.
- Persisted pre-resolution response windows with responder authorization, decline,
  offered choices, stale/duplicate rejection and single resumption/budget consumption.
- CPU responses, owner-scoped setup-data remapping on restart, no-store responses
  and client identity-change handling.
- `mvn -q test`, rolled-back SQL ownership checks and isolated four-user HTTP
  integration checks passed. V34 applied on existing and clean databases.

F07 now adds per-unit queues and effects on top of private choices. The
registered attack response opens before rolling, and token effects remain the
existing simplified adapter. Correct post-roll combat/defenses remain F10/F11.
New captures use event schema 3. Existing captured rule configurations are retained.

## Queued orders milestone (F07)

See [ORDERS_CONTRACT.md](ORDERS_CONTRACT.md) for definition configuration, actions,
privacy, conversion rules, reusable effects and verification instructions.

- Owner-private FIFO queues, capacity, committed prefixes and tail replenishment.
- Explicit reveal, public token banks, capacity/type limits and chosen replacement.
- Atomic order/token combinations and once-per-type-per-round resolution.
- Configurable speed/yaw, budgeted health/shield restoration and transfer, attack
  dice/rerolls, and coordinated child activations with separate budgets.
- Player controls, private-draft persistence, historical order replay, CPU planning
  and resolution through the same engine, and V35 catalogue configuration.
- Engine/API tests and isolated four-user HTTP + headless browser checks passed.
- V35 applied successfully on the clean integration database and the existing
  local database; deployed definition 4 exposes all four order types. The rebuilt
  application is running at `http://localhost:8080`.

Current bounds: numeric restoration rather than damage-card removal, automatic miss
rerolls in the simplified combat adapter, and approximate center-distance support
range. Full combat timing, geometry, keywords and card effects remain their own
milestones. **M5 now supplies definition-driven movement/geometry; F08 client integration
follows through those shared module and preview contracts.** See `RULES_LANGUAGE_BACKLOG.md`.
