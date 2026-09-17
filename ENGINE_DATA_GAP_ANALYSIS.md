# Engine Data Gap Analysis

This document records the current path from the versioned catalogue to the
authoritative simulator and the data that is currently lost at each boundary.

## Current Pipeline

1. The importer stores typed unit characteristics in
   `game_engine.unit_variant_characteristic` and generic cards, profiles,
   tokens, dials, and abilities in `game_engine.game_object`.
2. `game_engine.fn_engine_definition_get` projects each unit variant into a
   small JSON profile.
3. `DefinitionRepository` maps that JSON into `UnitDefinition`.
4. `AuthoritativeGameEngine` and `CpuBattleRunner` use `UnitDefinition` and
   `GameState` to validate and apply actions.
5. `simulator-session.html` renders the same reduced profile and runtime state.

## Confirmed Catalogue Inventory

The published Armada revision currently contains:

| Data | Count | Current use |
| --- | ---: | --- |
| Unit variants | 165 | Projected into engine unit definitions |
| Variant characteristics | 1,745 | A typed subset is projected; richer catalogue objects remain outside runtime |
| Unit type characteristics | 0 | No inheritance source is populated |
| Generic game objects | 2,952 | Admin/catalogue display only for the engine |
| Variant/object associations | 2,598 | Not loaded into runtime definitions |
| Object eligibility rows | 1,182 | Not used for fleet or loadout validation |
| Mode/object associations | 144 | Not loaded into runtime setup |
| Scenario/object associations | 72 | Not loaded into runtime setup |
| Fleet unit limits | 32 | Partially copied into CPU options by the setup UI |
| Terrain types | 8 | Reference/catalogue display only |

Characteristic coverage includes `Max Speed`, `Speed`, `Command`,
`Engineering`, `Squadron`, `Hull Value`, all four firing arcs, all four shield
zones, `Battery`, `Anti-Squadron`, `Defense Tokens`, `Upgrade Bar`, `Keywords`,
and `Squadron Ability`.

## Gap Matrix

| Area | Source data | Projection/runtime status | Severity | Next implementation |
| --- | --- | --- | --- | --- |
| Ship speed | `Max Speed` | Projected with `Speed` fallback; published ship profiles no longer resolve to zero | Done | Add broader regression coverage across published variants |
| Movement distance | Mode option `maximumMovementDistance` | New maneuver actions use `distancePerSpeed`; legacy coordinate actions remain for existing callers | Critical | Remove the legacy path after clients migrate |
| Turning/manoeuvre | The BSData catalogue has no structured maneuver characteristic | Ship charts are imported into `unit_variant_maneuver_profile` from the pinned Armada TTS definitions; each speed row preserves its variable joint count | Partial | Verify source licensing and reconcile chart differences against the target rules reference |
| Base geometry | Generic base objects/profiles | A radius is projected and used for movement collision; exact base shape/size is still absent | Partial | Add base shape/size and use it for range and arc checks |
| Hull zones | Four shield characteristics and arc names | Front/left/right/rear shield state is projected, persisted, and damaged by the target-facing zone | Partial | Add zone-specific hull/critical damage and redirect effects |
| Ship attacks | Four firing arcs and `Battery` dice | Colored dice are projected per arc, filtered by range band, and rolled against the legal firing arc | Partial | Add critical icons, weapon-specific damage, and full attack effects |
| Squadron attacks | `Anti-Squadron`, `Squadron`, and squadron abilities | Anti-squadron and battery profiles are projected separately for ship/squadron target selection | Partial | Implement squadron abilities and complete target-specific rules |
| Range | Profile source has attack data | Short/medium/long bands are enforced using color eligibility and mode-coordinate defaults of 100/200/300 | Partial | Load authoritative range scale from mode/rules data |
| Arc legality | Four arc names in source | Server-side bearing checks now select the available firing arc and reject attacks outside it | Done | Add exact base geometry and boundary-rule tests |
| Defense tokens | Token names and counts | Count is aggregated; token type/effects are discarded | High | Model token instances/types and per-attack spend/refresh rules |
| Command values | `Command`, `Engineering`, `Squadron` | Not projected or stored in runtime | High | Add command dials, reveal timing, and command effects |
| Keywords | Structured keyword values | Stored in catalogue but not runtime | High | Normalize keyword vocabulary and implement effects incrementally |
| Squadron abilities | Structured ability values and ability objects | Not executable | High | Add ability definitions/effects and timing hooks |
| Upgrade bars | Slot arrays and upgrade card objects | Not in runtime fleet/unit model | High | Add loadout selections, eligibility, costs, and modifiers |
| Unit points | Unit/card source data | Fleet points are user-entered and not validated | High | Project effective cost and validate fleet totals |
| Fleet legality | Format/faction/limit/eligibility tables | CPU uses only ship/squadron count options | Critical | Add a fleet validation service with explained violations |
| Factions/alliances | Format faction links and object eligibility | Basic faction ownership only | Medium | Apply format legality and alliance restrictions |
| Modes | Mode JSON, ordered phases, mode/object links | UI reads config; start sends copied scalar options | High | Load selected mode into an immutable runtime definition |
| Scenarios | Scenario JSON, board/object links | Session stores IDs; engine receives none | High | Load scenario setup, objectives, scoring, and victory rules |
| Phases/actions | Phase/action definitions and configs | Phase order is hardcoded in Java | High | Resolve phase/action availability from selected mode |
| Objectives | Objective cards and scenario links | Not instantiated or scored | High | Add objective setup, tokens/zones, scoring, and end conditions |
| Boards/deployment | Board dimensions and scenario board link | Dimensions are request fields; deployment zones absent | High | Load board definition and scenario deployment zones |
| Terrain | Terrain names and characteristics | No geometry or runtime obstacle state | Medium | Add terrain geometry and movement/obstruction effects |
| Runtime units | Fleet unit rows and engine units are separate | CPU has a fragile variant-id/code sync path | Medium | Resolve unit variants by stable IDs and persist a single runtime instance mapping |
| Persistence | Snapshot JSON plus event rows | Zone shields, attack metadata, engine logs, and client replay are persisted through the snapshot/event path | Partial | Add versioned schema and deterministic server-side replay checks |

## Speed and Turning Finding

The previous `UnitDefinition.maximumMovementDistance` field conflated two
different concepts:

- Armada `Max Speed`/`Speed` is a profile value used to select a maneuver
  setting.
- `distancePerSpeed` is the board-coordinate conversion supplied by the mode.

The mode and UI currently set `distancePerSpeed` to `100` mm. The maneuver
engine uses unit speed to calculate travel and the imported per-ship maneuver
chart to calculate heading changes. The remaining realism gaps are source
licensing verification, chart reconciliation against the target rules
reference, and formal navigation-command timing.

## Priorities

1. Reconcile the imported per-ship maneuver charts against the target rules
   reference and pin an approved source/licence.
2. Complete zone-specific damage, defense-token effects, critical icons, and
   weapon-specific attack rules.
3. Load selected mode/scenario/fleet format into runtime setup and enforce
   points, limits, objectives, deployment, and phase configuration.
4. Add fleet legality, upgrades, keywords, abilities, terrain, and richer runtime persistence
   behind the stable profile contracts.
