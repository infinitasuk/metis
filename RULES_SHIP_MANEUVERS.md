# Source-backed small-ship maneuver training

`armada-ship-maneuvers-v1` is the first interpreted ship-movement slice. The generic
`/rules` client and CPU submit the same speed, joint-yaw and tool-side inputs to the
authoritative `maneuver:1` module. The pack is a closed movement-training format.

## Play and supported scope

An administrator imports a published Armada catalogue revision using:

```json
POST /api/v1/admin/rules/catalogue/imports
{"gameVersionId":4,"pack":"armada-ship-maneuvers-v1"}
```

Use the published revision ID for the installation; `4` is the local development
revision. Import publishes **Standard** (six rounds) and **Short course** (three
rounds). In `/rules`, select Small-ship maneuver training, choose a faction and one
ship per seat, then assign human or CPU controllers. Both ships cost **44 points**:

| Profile | Faction | Hull | Maximum speed | Yaw limits at speeds 1, 2, 3, 4 |
| --- | --- | --- | --- | --- |
| CR90 Corvette A | Rebel Alliance | 4 | 4 | `[2]`, `[1,2]`, `[0,1,2]`, `[0,1,1,2]` |
| Raider I-class Corvette | Galactic Empire | 4 | 4 | `[2]`, `[2,2]`, `[0,1,1]`, `[0,1,1,1]` |

Each seat has a 44-point, exactly-one-ship roster policy. Existing personal presets,
source-aware roster validation and immutable starting-roster capture apply. Either
seat can choose either faction, including matching factions. Deployment alternates
through owner-specific zones. Each subsequent activation requires one maneuver,
including speed zero if desired; automatic round scoring ends the course with
`maneuver-training-complete` and no winner.

The training action lets the player choose speed anew each activation. Navigate
commands/tokens, speed-change restrictions, yaw bonuses, ship combat, shields,
defense-token effects, obstacles, squadrons, upgrades and full fleet-format rules
are outside this pack. The public import scope lists those boundaries explicitly.

## Source capture and calibration

V44 adds immutable `catalogue_unit_maneuver` rows, a separate
`bsdata-armada-ships-47bfca1` cost set and
`fn_rules_catalogue_maneuver_source(version,names,costSet,maneuverSet)`. It leaves
prior migrations, cost sets, source projections and legacy maneuver tables intact.
An import captures the recipe/template hashes, profile values, costs, charts,
calibration, source URLs, commit hashes and source-file SHA-256 values. Later imports
cannot change an existing definition/session. Missing costs, charts, provenance,
inconsistent maximum speeds or mismatched base/tool geometry reject the import.

- Costs and profile characteristics: [BSData/star-wars-armada](https://github.com/BSData/star-wars-armada),
  commit `47bfca1bddea459678568e5ee820597a433d9b41`, CC BY-SA 4.0.
  CR90 entry `84f9-c194-d8cd-e718` in `Rebel Fleet.cat` revision 55;
  Raider entry `e58d-8015-91be-811a` in `Imperial Navy.cat` revision 56.
- Charts and geometric reference: [Valadian/TabletopSimulatorIncludeDir](https://github.com/Valadian/TabletopSimulatorIncludeDir),
  commit `598f7741a7e3d09dd0e362b47914dd5d54ec1fe9` (source licence not specified).
  Ship charts come from `TTS_armada/src/defs/{rebel,empire}/ships.ttslua`.
  `Magic Ruler.ttslua` supplies the unscaled segment and yaw angle;
  `Global.ttslua` supplies small-base half extents and the attachment offset.

`powershell -NoProfile -File tools/verify-armada-ships.ps1` independently fetches the
pinned sources and prints entry costs, characteristics, file hashes, converted yaw
charts and derived calibration. All six source-file hashes are recorded in V44.

| Quantity | Captured value in `tts` coordinates | Derivation |
| --- | --- | --- |
| Board | 72 × 36 | Authored training board |
| Base forward length / across width | 2.796 / 1.614 | Twice the source half extents 1.398 / 0.807 |
| Segment length | 2.6265 | Unscaled `extendSelf` / `moveChild` stride |
| Yaw per click | 22.5° | Source joint rotation |
| Attachment forward / across | 0.618 / ±1.127 | `1.398 - 0.78`, `±(0.807 + 0.32)` |
| Tool collision width | 0.64 | Authored capsule envelope, twice the lateral attachment clearance |
| Tolerance | 0.000001 | Authored numerical tolerance |

`tts` is an explicit coordinate label, **not a millimetre conversion or physical
ruler certification**. This calibration reproduces the pinned unscaled tool's
rigid-body placement; the capsule envelope is not its plastic/mesh outline.
World x increases rightward, y downward; heading zero points along +x and positive
angles turn clockwise. Port attachment is negative local across, starboard positive.
Positive yaw turns starboard, negative yaw port. Rectangle `width` is forward along
local x; `height` is across along local y.

## Reusable module contract

Pin `"maneuver":"1"` with `"space":"1"`. These optional capabilities retain the
compiler5/runtime4 boundary; old pinned definitions retain their fingerprints. No
legacy battle types or game/unit identity checks enter the interpreter.

| Operation | Meaning |
| --- | --- |
| `maneuver.move` | Validate and commit one maneuver; emit public `rules.maneuvered` |
| `maneuver.canMove` | Pure boolean query using the same planner; budget exhaustion propagates |
| `maneuver.options` | Pure bounded list of legal yaw lists for a chosen entity, speed and side |

All three take `entity`, integer `speed`, string `side` (`PORT` / `STARBOARD`),
`chart:list<list<integer>>`, decimal `segment`, decimal `angle`,
`anchor:list<decimal>` (`[forward, acrossMagnitude]`), decimal `toolWidth` and boolean
`collisions`. Move/canMove also take `yaw:list<integer>`. The training definition
binds chart/calibration from captured data; clients can submit only its four action
parameters: entity, speed, yaw and side.

Charts have speed-zero row `[]`; row n contains exactly n nonnegative yaw limits.
The module permits up to eight joints and limits of 0–8, while this pack uses four
joints and limits of 0–2. Speed and each signed yaw must fit the selected row.
Calibration must be finite: positive segment ≤1e9, angle in `(0,90]`, bounded
attachment offsets and nonnegative tool width. The subject must be placed, on a
continuous board and owned by the actor.

Planning starts at the ship's transformed attachment point. Each segment advances
along the current heading; its joint then rotates by `yaw × angle`. Subtracting
the newly rotated attachment offset gives the ship centre at that joint. The final
centre and normalized heading define the destination. `StrictMath` binary64
geometry and the existing decimal serialization contract apply.

The full final base must fit on the board. It must clear the used tool's capsule
segments (touching allowed within tolerance). With `collisions:true`, it must also
clear every other placed solid base using the board's touching policy. Intermediate
centres/tool segments may pass outside the board or over other ships. This is a
**final-pose rejection policy**: no automatic speed reduction, overlap damage or
off-board destruction is implemented. Speed zero retains the current pose.

Suggestions try straight joints first, then alternating negative/positive clicks,
with at most 256 complete plans examined and 64 results, further limited by the
definition's collection/instruction budgets. They are bounded suggestions, not an
exhaustive search. Manual inputs receive the same authoritative validation.

## Client, preview and recovery

A movement action can declare this optional typed control binding:

```json
"maneuver": {
  "entity":"entity", "speed":"speed", "yaw":"yaw", "side":"side",
  "chart":{"get":{"entity":{"var":"entity"},"field":"chart"}}
}
```

The compiler checks distinct parameter types, movement preview/module pins and an
entity-phase subject binding. The chart expression can read the acting player,
active entity and visible state; hidden data fails closed. The candidate DTO
contains the evaluated chart so the browser can render speed, side and per-joint
controls without any Armada-name branch. These controls are input aids; commit
validation remains authoritative.

`POST /api/v1/rules/sessions/{id}/preview` uses the normal versioned action request:

```json
{"actor":"alice","action":"maneuver","parameters":{"entity":"seat0-ship0","speed":2,"yaw":[0,0],"side":"PORT"},"expectedStateVersion":6}
```

The isolated action planner returns the ordinary movement DTO plus optional
`maneuver:{speed,yaw,side,tool,toolWidth}`. `path` is the sequence of derived centres;
`distance` sums the centre-path segments, **not** the tool length. The browser draws
that path, the tool envelope/joints, final rotated footprint and heading. It shows
the destination in the board's own units. Draft inputs survive reload. Preview
responses are ignored after inputs, actor, action, version or replay context change.

Commit emits `rules.maneuvered` with entity, speed, yaw, side, x, y and heading, and
records the pose through the existing atomic checkpoint/delta/receipt pipeline.
Rejected plans write no pose. Replays use recorded results without recalculating
geometry. The shared CPU consumes the same projected offers as human clients.

## Verification

```text
mvn -B "-Prules-full,rules-mutation" clean verify
```

- `ManeuverConformanceTest`: independent right-angle geometry oracles, mirror/rotation
  invariants, chart limits, speed zero, all straight speeds/sides, pose/preview parity,
  exact replay/restore, owner/subject checks, bounds, collisions, tool overlap, hidden
  charts, budgets, invalid control bindings and complete CPU courses.
- `ShipManeuverCatalogueTest`: data-driven profile/calibration import, shared roster
  policies and rejection of missing/malformed/mismatched source data.
- `ship_maneuvers.sql`: source entry IDs, costs, hulls, exact charts/calibration,
  source hashes, immutable rows, missing sources and old-projection compatibility.
- `ship-maneuvers.cjs`: idempotent imports, presets, HTTP authorization and invalid
  geometry, rollback, process kills/restarts, exact retries, human/CPU completion,
  browser joint controls/drafts/preview, delayed-preview rejection, lost-acknowledgement
  retry after reload and historical/live pose agreement.
- Full suite also retains the existing legacy, squadron, privacy and cleanup gates.
  Focused mutation targets include both maneuver classes.

### Local qualification and delivery — 2026-10-11

- `mvn -B "-Prules-full,rules-mutation" clean verify` passed: **363 Java tests**
  (311 core, 47 API, 5 web), no failures/errors/skips; package coverage gates passed.
- Focused mutation gate: **650 / 757 detected (86%)**, including the maneuver targets.
- Six SQL suites and all 14 API/browser integration suites passed, including real
  process restart, exact retry/replay and the intentional-failure cleanup contract.
- Full report: `target/rules-suite/metis-rules-1791718926429-78887c5d/summary.json`.
  Preview image: the same directory's `ship-maneuver-preview.png`.
- The browser dependency-install fix also completed in a fresh Ubuntu 24.04 container
  as a non-root user with sudo, Node 24.16.0 and pinned Playwright 1.58.2.
- Rebuilt local Compose app (`ac809c3-dirty`), applied **V44**, and imported catalogue
  entries/definitions **13** (Standard) and **14** (Short course). The 12 previous
  entries were preserved. Local Chromium smoke created a temporary private game,
  matched a curved preview to the committed pose, reloaded/replayed it and removed
  the temporary game. Screenshot: `target/ship-maneuver-local.png`.
- Available at `http://localhost:8080/rules`. These results record local qualification;
  check GitHub Actions for hosted results on the committed revision.
