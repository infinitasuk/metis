# M5 — Spatial rules, scenarios and headless CPU conformance

The shared interpreter executes three complete synthetic games using versioned JSON.
`space:1` supplies geometry and movement; `scenario:1` supplies roster validation and
damage. Setup, targeting, reactions, objectives, scoring and endings remain authored
procedures and expressions. See the packaged examples under
`engine-core/src/main/resources/rules/examples/`.

## Run a complete game

```text
mvn -q -pl engine-core -Prules-cpu compile exec:java
mvn -q -pl engine-core -Prules-cpu "-Drules.source=engine-core/src/main/resources/rules/examples/vector-orders.json" compile exec:java
mvn -q -pl engine-core -Prules-cpu "-Drules.source=engine-core/src/main/resources/rules/examples/hidden-grid-cpu.json" compile exec:java
```

The default is `grid-skirmish`. The CLI reports the definition fingerprint, command
count, outcome, winners and round. It validates/restores each checkpoint and checks
each committed delta against replay. Its 300-command operational cap reports an
error if reached; it does not impose a game ending. Dice use the committed random
stream; exact seeded/tape oracles remain available in Java conformance tests.

| Definition | Flow and mechanics | Ending |
| --- | --- | --- |
| `vector-orders` | Simultaneous private FIFO planning, alternating ship activations, continuous circular bases, symbolic dice, shield absorption and defender guard | Immediate elimination; authored ten-round draw limit |
| `grid-skirmish` | Two pieces per seat, whole-player entity phase, optional move/attack steps, Manhattan paths, defender reactions, deployment zones and points budget | Immediate elimination; authored eight-round objective count, including ties |
| `hidden-grid-cpu` | Private setup choices, alternating cell guesses, no dice or movement | Eliminate the opposing hidden target |

These are engine acceptance examples, not certification of licensed game rules.
The existing legacy battle UI and numeric sessions retain their captured execution
path. M6 now provides mode composition and a bounded catalogue migration (see
`RULES_MODES_M6.md`); M7 now supplies the generic browser and persistent CPU seats
described in `RULES_CLIENT_M7.md`.

## Version and persistence boundary

Spatial/scenario modules, finite action choices, nonzero priorities or movement
preview annotations select `rules-compiler-5` / `rules-runtime-4`. Definitions using
only the previous mechanics retain compiler4/runtime3; previous core-only definitions
retain compiler3/runtime2 and their original fingerprints. All three contracts remain
readable. Adding spatial rules alone does **not** allocate an entropy stream.

Positions and damage are ordinary declared entity attributes/resources and use the
existing atomic checkpoint/commit/idempotency pipeline. No database migration is
required for M5: Flyway remains at V37. Invalid placed poses are rejected both before
commit and when restoring a checkpoint. Past commits replay recorded results without
recomputing geometry or rolling dice.

## Coordinates, shapes and numerical semantics

Root `space` contains `boards`, entity-type `bodies`, `movements` and rectangular `zones`.
Bindings are validated at publication; public spatial fields are mandatory in v1.

- Board dimensions are positive and at most 1e9. A grid uses integral width/height,
  integer x/y attributes and single-cell `POINT` pieces. Coordinates identify a cell's
  lower corner; its geometric footprint is the unit square centered at `(x+.5,y+.5)`.
- Continuous boards use decimal x/y, Euclidean path length, and `POINT`, `CIRCLE` or
  oriented `RECTANGLE` footprints. Circle/rectangle sizes must be positive. Point
  footprints have zero area; touching-allowed policy permits coincident continuous
  points. Grid occupancy always rejects an occupied destination when collisions apply.
- Heading attributes are decimal, in degrees from +x toward +y; committed headings
  normalize to `[0,360)`. Maximum turn is the shortest angular difference, 0–180°.
  x, y and heading must bind distinct attributes.
- Geometry deliberately converts typed numbers to Java strict binary64 arithmetic
  and uses `StrictMath` for transcendental functions. Results become decimal values
  through `BigDecimal.valueOf`; geometry does not silently round to cells or ruler ticks.
  Authors can apply the language's explicit arithmetic/rounding operators separately.
- Each board declares a tolerance between 1e-9 and 1e-3 in its coordinate units.
  Bounds and distance allowance include that tolerance. With touching allowed,
  overlap requires penetration beyond tolerance; otherwise tangency within tolerance
  blocks. Board-edge contact is allowed within tolerance independently of obstacle
  contact policy. Choose units/tolerance appropriate to binary64 resolution.
- `unit` is a returned label, not an automatic conversion system. Multiple boards
  are supported; cross-board targeting and deployment are rejected.

## Movement and deployment

`space.move(entity, policy, path, heading)` takes a bounded nonempty list of `[x,y]`
waypoints, excluding the starting position. It validates placement, optional root
action-actor ownership, path length, board bounds, turn limit and collisions before
updating any committed pose. An entity attribute supplies the allowance. Costs,
speed changes, terrain effects, movement timing and action budgets are defined by
the surrounding procedure/flow.

- Grid paths enumerate adjacent cells. Manhattan disallows diagonal steps;
  Chebyshev/Euclidean allow them with their respective costs. `cornerCutting:false`
  also checks both orthogonal neighbours during swept diagonal traversal.
- `SWEPT` checks every grid cell or the analytic continuous sweep. Thin obstacles
  cannot be missed by coarse point samples. `DESTINATION` permits crossing intervening
  occupants while still validating distance, waypoint bounds and final occupancy.
- Continuous translations keep the original base orientation along every segment.
  A rectangle's final rotation additionally requires its full circumcircle to fit
  within the board and clear solid obstacles. This is intentionally conservative;
  arbitrary rotating-base sweeps, curved manoeuvre templates, height and 3D are not
  part of this v1 contract.
- `collisions:false` and `ownerOnly:false` are explicit definition policies. For
  reaction movement with a different subject, the reaction's author can bind that
  subject and use an appropriate policy; ownership is never inferred from game names.
- Movement emits a public `rules.spaceMoved` event with subject, final pose and distance.

`space.deploy(entity, zone, x, y, heading)` requires an unplaced entity, matching board,
full footprint containment and an unoccupied destination. `ownerSeat` is a player-list
index; `-1` permits any owner. A shared zone can be used for objectives as well as setup.

## Pure spatial queries

| Query | Contract |
| --- | --- |
| `space.canMove(entity,policy,path,heading)` | Same planner as committed movement; work-limit exhaustion remains an error |
| `space.distance(from,to,mode)` | `CENTRE`: Euclidean centers; `EDGES`: minimum footprint separation, clamped at zero; `BOARD`: board metric |
| `space.lineOfSight(from,to)` | Center-to-center segment against placed opaque footprints, excluding source and target |
| `space.inArc(from,to,start,sweep)` | Relative angular sector from the source heading; sweep 0–360°, boundary tolerance included |
| `space.inZone(entity,zone)` | Placed footprint fully inside the matching board's zone |
| `space.paths(entity,policy,targets)` | Bounded deterministic suggestions, each validated by the same planner |

Grid suggestions explore shortest-cost reachable cells within collection/work limits.
Continuous suggestions try directions toward targets and eight compass headings at
full, half and quarter allowance. Suggestions sort by nearest target; they are a
bounded baseline search, not an exhaustive route finder. Terrain can be represented
by ordinary bodies with independently bound solid/opaque flags. Range bands, cover
modifiers and keyword meaning are expressions/procedures over these facts.

## Scenario operations

- `scenario.require(condition,code)` rejects atomically with `SCENARIO_REQUIREMENT`;
  its author-supplied label is the headless diagnostic message.
- `scenario.roster(owner,entities,policy)` checks distinct identities, entity type,
  ownership, min/max count, nonnegative points and total budget. Optional string
  attributes supply unique names (empty means unrestricted) and an allowed-faction
  list. It validates the supplied roster; it does not choose or infer a catalogue army.
- `scenario.damage(target,policy,amount)` consumes ordered absorber resources before
  the health resource and returns actual health damage. Amount must be nonnegative;
  absorber/health resources are distinct with zero minimum. `capAtRemaining` either
  caps overkill or lets the normal resource-bound rejection apply. At zero health,
  `KEEP` leaves the entity, `REMOVE` removes it and associated mechanical references,
  and `MARK` writes `false` to the declared boolean marker.

Elimination, initiative/order, objective control, score arithmetic, ties, round caps
and refresh are explicit JSON. The examples check elimination immediately after
damage and scoring at automatic phase entry; passing cannot skip the terminal check.

## Preview and projected choices

An action may declare `"preview":"MOVEMENT"`. The compiler requires a reachable
`space.move` and rejects random/decision operations in its procedure/callees. Preview
executes the deterministic action on an isolated seat-visible snapshot using the same
arguments, costs, budgets, effects, phase completion and planner as `act`. Random or
decision work encountered in a subsequent phase fails closed with an explicit
`PREVIEW_*_REQUIRED` code. Hidden inputs fail as unavailable rather than probing an
opponent's data. These codes indicate preview unavailability; commit remains authoritative.
Only movement plans are returned, never speculative private state or random outcomes.

The existing HTTP route remains:

```json
POST /api/v1/rules/sessions/{uuid}/preview
{"actor":"alice","action":"move","parameters":{"entity":"alice-a","heading":0,"path":[[2,1],[3,1]]},"expectedStateVersion":2}
```

The response includes `allowed`, `code`, `version`, and `movements` containing
`entity`, `board`, `unit`, `from`, `to`, `path` and `distance`. Version/ownership checks
are shared with the existing service boundary. Preview and offers write no checkpoint,
event, receipt or entropy position. Other actions retain eligibility-only previews:
they do not predict attacks, hidden guesses or reactions.

Optional action `choices` maps input names to typed list expressions. Names expand
in sorted order, and later domains may refer to earlier chosen parameters plus
`actor`, `players`, `round` and visible state. Forward dependencies fail compilation.
The active entity binds the phase subject. Complete domains produce finite suggestions
in `/actions` under each owned actor's `offered`; arbitrary manual inputs remain valid
through the same command validator. Expansion keeps at most `min(256,maxCollectionSize)`
combinations/results, with at most 256 full eligibility/preview attempts across actions.
Definition `priority` orders actions; a missing/unavailable domain produces no suggestion.

`RulesCpu.choose` accepts only `RulesCpu.Input`: an acting-seat `RulesView`, offered
actions, controls and eligible entities. It chooses the first pending option, first
eligible activation, highest-priority action, then advance/pass. It never receives a
checkpoint, continuation, entropy stream or rule executor; an architecture test guards
that boundary. Every chosen command returns through the ordinary versioned runtime API.

## Verification

```text
mvn -B "-Prules-full,rules-mutation" clean verify
```

Coverage gates remain 90% line / 85% branch for language and runtime packages.
Focused mutation testing now includes spatial geometry/planning and scenario damage.
Tests cover analytic swept collision, shapes/tangencies, LOS/arcs, grid corners,
deployment/rosters, damage/overkill/zero policies, exact movement costs, hidden-input
queries, rejected-state immutability, scoring on phase transition and complete CPU play.
Generated tests compare circle geometry and cell movement to independent small models.
Hidden-grid CPU offers are invariant under changing an opponent's concealed cell.

The full isolated suite runs all three games through authenticated HTTP offers,
compares movement preview with committed coordinates, checks all viewer roles,
retries commands, kills/restarts the application and verifies historical replay.
Existing SQL, legacy browser, reaction, RNG recovery and failure-cleanup gates remain
mandatory. Local runs are recorded under `target/rules-suite`; hosted CI execution
remains a separate repository/runner action.

### Local delivery record — 2026-09-22

- Combined clean/full/mutation command passed: **314 Java tests**, no failures,
  errors or skips; six properties exercised **378** generated/exhaustive cases.
- Expanded focused mutation run detected **525 / 610 mutants (86%)**. Both package
  coverage gates passed. SQL, Chromium, interpreted API, RNG recovery, spatial HTTP
  completion and intentional-failure cleanup all passed.
- Full report: `target/rules-suite/metis-rules-1790099961227-28e20b9e/summary.json`.
- Rebuilt/deployed the local app at `http://localhost:8080`; login and authenticated
  rules API smoke checks passed. Flyway remains V37.
- Session 139 remains paused at round 286, version/event count 8407. Its saved-state
  checksum before and after deployment is `350934b1539e9e029a09f4737f227853`.
