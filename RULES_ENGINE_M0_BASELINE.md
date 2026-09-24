# M0 baseline: hardcoding inventory, v1 scope and migration boundary

**Delivery:** implemented and locally verified on 2026-09-20. A clean
`mvn -q -Prules-full clean verify` passed 84 Java tests, runner contracts, three SQL
suites, fresh migrations/repeat startup, four-user HTTP checks and mandatory Chromium
browser checks. The injected post-startup failure returned nonzero and cleanup was
verified. No suite containers or networks remained. CI is configured for Linux and
Windows fast checks and Linux full acceptance; hosted execution awaits the repository
workflow. See [runner instructions](tools/rules-tests/README.md).

## Architecture decision

The delivery target is a **bounded, typed, JSON procedure language**, compiled before
publication and run by a framework-independent interpreter. Java supplies interpreter
instructions and mechanical algorithms; definitions supply game names, policies,
parameters, timing, resource meanings and compositions. There is no embedded JavaScript
and no interpreter fallback to the legacy battle switch.

This is the R01/M0 implementation map. Source references use class/function names so
they remain useful as line numbers change. **Inventory state: inspected, not migrated.**

## Current execution path

`BattleApiController.startSession` loads/captures a `GameDefinition`, builds the board
and initial units, and persists state. `submitAction` authenticates the actor and checks
the state version. `AuthoritativeGameEngine.executeAction` chooses `ActivationEngine`
when a `TurnStructure` exists; its callback still calls a fixed `applyEffect` switch.
`ResponseWindows` can suspend an attack before that effect. `OrderEngine` is another
fixed effect implementation. CPU actions reach the same engine, but its strategy and
the browser also calculate movement/targeting assumptions.

Configuration of a sequence is therefore already possible, but configuration of an
arbitrary executable action is not. A new phase does not remove the hardcoded mechanics
called from that phase.

## Inventory and intended destination

Paths below are relative to `engine-core/src/main/java/com/metis/engine/core` (Core),
`engine-api/src/main/java/com/metis/engine/api` (API), or `webapp/src/main` (Web).
**D** = definition data, **I** = interpreter/platform invariant, **M** = registered
mechanical module. Existing behavior stays legacy-only until its replacement passes.

| ID | Current source and assumption | Destination and acceptance requirement | Delivery / F-task |
| --- | --- | --- | --- |
| H01 | Core `AuthoritativeGameEngine.validateSetup`: one board, faction uniqueness, player/CPU counts | D setup/roster constraints; I identity/ownership checks. Same-faction games must be configurable | M1/M5, F13/F14 |
| H02 | Core `UnitDefinition`, `UnitState`, `BoardState`: hull/shields/command fields, implicit weapons, class-specific radii, continuous coordinates | D typed attributes/resources/traits; M spatial model; explicit legacy profile adapter without automatic battle defaults | M1/M5, F01/F09/F12 |
| H03 | Core `AuthoritativeGameEngine.advancePhase`, `endTurn`, `unitCanActInPhase`: command/ship/squadron/status sequence and class matching | D phase graph/eligibility expressions; I sequencing. No semantic dependence on identifiers | M2, F05/F12 |
| H04 | Core `ActivationEngine.stepFor`, `finish`, `enter`: configurable steps/groups, but fixed action names and default six-round ending | Retain tested sequencing concepts; D completion/round-limit/winner policies; I procedure frames and budgets | M2/M5, F05/F15 |
| H05 | Core `AuthoritativeGameEngine.applyEffect`: fixed start/deploy/move/attack/reset/end switch | I registered instruction dispatch; D action procedures. Unknown operations rejected on publication | M1/M2, F10/F17 |
| H06 | Core `deployUnit`, `defaultSpeed`; API `BattleApiController.startSession` and roster deployment helpers: initial speed, formation-to-board conversion | D setup/initial resources/regions; M placement validation. Keep transport outside rule evaluation | M5, F14 |
| H07 | Core `moveUnit`, `moveByManeuver`, `resettableMovement`: direct distance versus sampled path; speed fallback, human editing assumptions | M common preview/commit geometry; D movement/commit/edit policies; I tentative versus committed state | M5, F08/F09 |
| H08 | Core `MovementCalculator`: 22.5-degree clicks, twelve samples/segment, circular base collision callers | M geometry algorithms; D angle, shape, units and tolerances. Explicit numeric boundary contract | M5, F08/F09 |
| H09 | Core `attackPlan`, `eligibleDice`, `rangeBand`, `zoneForBearing`: squadron branches, colored dice by range, 100/200/300 bands and fixed arcs | D weapon pools, filters/range/zone policies; M distance/visibility queries | M4/M5, F09/F10/F12 |
| H10 | Core `attackUnit`: d6 >= 3 means hit, automatic miss rerolls, forced hit after three misses | D faces/pools/result tables and choices; M dice operation; I recorded random outcomes. No forced hit in the new path unless explicitly defined | M4, F10 |
| H11 | Core `selectedDefenseToken`, `attackUnit`: defense subtracts one, shield-before-hull damage, fixed exhaust behavior | D token meaning, cost and damage procedure; M generic resource update | M4, F11 |
| H12 | Core `attackUnit`: one surviving player wins; `ActivationEngine.nextPhase`: round-limit completion and fixed token readying | D elimination/scoring/ties/refresh and expiry procedures | M4/M5, F15/F18 |
| H13 | Core `OrderRules.Amount`, `OrderEngine.resolve/support`: typed stat bindings, fixed timing/effect kinds, child move/attack and round-spent ledger | Adapt FIFO/scoped resource concepts; D bindings, timing, effects and child procedures; I nested execution/budgets | M3/M4, F07/F17/F18 |
| H14 | Core `ResponseWindows`: one pending action, fixed target/resource fields, pre-attack callback | I persisted decision frames and audience; D triggers, responder priority, nested windows and decline behavior | M3/M4, F06/F11 |
| H15 | Core `PrivatePlanning`, `ScopedData`, `StateProjection`; API `BattleViews`; Web `BattleProjectionAdvice` | Retain I seat projection and confidentiality; D scoped resources/events and reveal rules. Extend projections to continuations/traces | M3, F06/F21 |
| H16 | Core `AuthoritativeGameEngine` default RNG and API controller/CPU engine construction: ThreadLocalRandom, no persisted stream position | I authoritative random port, committed tape/position and retry semantics; injected tape for exact module tests | M3/M4, F10/F21 |
| H17 | API `DefinitionSnapshot`, `DefinitionRepository`: exact captured JSON/checksum, legacy-v1/activation-v1 dispatch only | Extend I capture to resolved modes, language/interpreter/module versions; preserve exact existing captures | M3/M6, F04 |
| H18 | API `BattleApiController.submitAction`, `BattleRepository.saveEngineState`, database runtime routines: optimistic version/transaction boundary, no general continuation/idempotency protocol | Retain I transaction/version enforcement; add actor/session command identity and atomic event/continuation writes | M3, F03/F04/F21 |
| H19 | API `CpuBattleRunner`, `CpuOrders`: fixed deployment constants, phase branches, attack/move preferences, resource heuristics and movement fallback | I decision-provider contract; optional strategy separate from legality; M previews and D legal choices | M5/M7, F19 |
| H20 | Web `resources/templates/simulator-session.html`: local range/arc/movement math, fixed action controls, event-type replay reducer | M server previews; I generic typed choices/projected event state; D labels. UI preserves interaction state but decides no legality | M7, F08/F20/F21 |
| H21 | Web `simulator-game.html`, `CatalogController`; API `FleetPresetRepository`; SQL bootstrap 030/031: formation/budget UI, count-based CPU building, estimated points | D roster/cost/setup policies and provenance; common validation through API; estimates explicitly not canonical | M5/M6, F13/F14 |
| H22 | Core existing self-play fixture, Web static terrain/objective rendering, upgrade/catalogue records | Replace self-play phase assumptions with decisions; D terrain/objectives/effects only when executable modules exist. Decorative/catalogue presence is not runtime support | M5/M7, F16/F17/F19 |

## Supported-v1 acceptance scope

This table records the scope established at M0. The M1 schema/compiler is now delivered
and documented in `RULES_LANGUAGE_V1.md`; execution and later mechanical modules follow
the milestones below.

| Capability | Required by | Boundary |
| --- | --- | --- |
| Versioned definitions, identifiers, types, references and compile diagnostics | M1 | Unknown operations/versions fail publication; no silent defaults |
| Entities, ownership, traits, numeric/boolean/text attributes, bounded collections and resources | M1/M2 | Existing hull/command fields are explicit imported bindings only |
| Scoped variables, selectors, arithmetic/comparison/rounding, sequences, if, bounded forEach, choices, resource changes, event emission | M1/M2 | No arbitrary code, IO, wall-clock reads or unbounded recursion |
| Alternating players/entities/groups, whole-player and simultaneous-plan phases, required/optional budgets, automatic steps | M2 | Every transition and terminal condition has declared behavior |
| Durable decisions, nested bounded reactions, responder order, decline/pass and visibility | M3 | Pending decisions survive restart; no transaction stays open while waiting |
| Numeric/symbolic weighted dice, pool changes, selectable rerolls/cancellation and result tables | M4 | Rolls are recorded server-side; previews consume no RNG |
| Token capacity/cost/exhaust/discard/refresh; private orders, decks/hands; stacking and expiry | M4 | Cost/effect commits atomically; scope applies to derived output too |
| Addressed grid and measured continuous/path models; bounds/occupancy/base/zone/range/line-of-sight modules | M5 | Explicit distance/angle/rounding policies and common preview/commit validator |
| Setup constraints, deployment, objective scoring, elimination, round limits and ties | M5 | Small declared examples, not every licensed rule or fleet format |
| Resolved base/mode overrides, immutable captures, compatibility and generic decision clients | M6/M7 | Same binary executes the contrasting fixtures and migrated supported catalogue slice |

Deferred from v1: full licensed-game conformance, campaign persistence, every named
card/keyword, specialist formats, arbitrary scripting, mobile/VR clients, advanced
AI and rich visual rule editing. Unsupported mechanics must be explicit; neither a
UI label nor a legacy fallback may masquerade as interpreter support.

## First headless proof (M2)

1. **Resource race:** two players alternate `collect` or `boost`; boost spends a
   resource for a defined score increment. A target score ends the game. Test
   insufficient funds, passing/budgets, changing the threshold and changing phase
   order using JSON only. There is no movement, dice, shield or command assumption.
2. **Hidden grid:** private setup places single-cell targets; players alternate cell
   guesses. Repeated/invalid guesses are rejected, hits are revealed as defined, and
   finding all opposing targets ends the game. The M2 pure grid-address rules use
   sets/cell equality; full spatial modules follow in M5.

Both must run on the new interpreter, not an `ActivationEngine` callback that embeds
those games in Java. M0's scenario harness exercises the **current adapter** solely
as a regression/test-infrastructure proof; it does not count as either M2 game.

## Persistence, determinism and compatibility decisions

- Use typed compiled instructions and immutable input/output state. Bound instruction
  count, collection iteration and reaction depth per submitted transition.
- Pure evaluation receives a context containing explicit IDs, logical time and a
  random source. Tests supply exact tapes and fixed time; production randomness must
  eventually be committed with state, not re-created during replay.
- Commit each accepted submission/resumption atomically with its events, continuation,
  resource deltas and random bookkeeping. Failed/stale submissions commit nothing.
- M0 adds no runtime schema change. Applied V32–V35 and existing snapshot bytes remain
  untouched. Add new migrations and language/module capture fields when M3 needs them.
- Keep existing captured `legacy-v1`/`activation-v1` sessions on explicitly supported
  compatibility adapters. New interpreter sessions have a separate versioned contract;
  dispatch occurs at the session boundary, never as a per-operation fallback.
- Replay applies recorded versioned events/outcomes, not current catalogue rules.
  Unsupported snapshots remain readable where possible with explicit incompatibility
  and a separate restart option; never silently heal or rewrite source history.

## M0 automation choice

JUnit/Surefire remains the Java baseline. The `rules-full` Maven profile invokes a
repository-owned Node runner after packaging. It uses Docker CLI for disposable
PostgreSQL/app containers, unique networks and allocated host ports, integrating the
existing HTTP/browser scripts and SQL checks without duplicating their assertions.
This is the orchestration adapter for T07; M3 subsequently reused it for focused SQL
fault-injection and actual process-restart tests. The runner must clean up on success,
assertion failure and interruption, and retain logs and a machine-readable result.

Pin the CI Node and browser dependency versions. Browser checks are mandatory in
`rules-full`; standalone legacy scripts can still be used independently. Tests never
select an existing development database. M0 also verifies an intentionally incorrect
scenario oracle and an injected post-startup suite failure, proving failure propagation
and resource cleanup rather than merely observing a passing test run.
