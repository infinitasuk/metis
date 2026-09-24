# Star Wars: Armada implementation review

Reviewed: 2026-09-19. Scope: current working tree, local published definition 4 (`armada_1_5`, game 6), live database projections, battle UI, CPU runner, and engine tests.

**Follow-up:** the initial game-neutral correction batch subsequently fixed the
profile projection, introduced tracked database upgrades, and rejected out-of-turn
attacks. See [GAME_ENGINE_FIX_BACKLOG.md](GAME_ENGINE_FIX_BACKLOG.md) for verified
delivery status. Findings below preserve the audit baseline; existing battle
snapshots are not retroactively repaired.

The following F04–F05 slice also added captured starting definitions, explicit
legacy restarts and configurable unit/group activation sequencing. The original
round/activation findings below describe the pre-fix baseline. F06 subsequently
added seat-scoped views and responses; F07 added private queues, token conversion,
and configurable command-effect primitives (see `ORDERS_CONTRACT.md`). Complete
post-roll combat, damage cards, geometry, keywords and scoring remain backlog work.

## Conclusion

METIS has a usable battle-simulator foundation and several useful Armada data structures, but it does **not yet implement a complete standard Armada game**. Some rules are missing; others are represented by simplified behavior that changes the game. There are also live data/projection defects that make the existing rules less accurate than the source code suggests.

The highest-impact gaps are the activation sequence, command system, attack resolution, movement geometry, squadron rules, and scoring. These need engine and runtime-state changes, not just more controls on the battlefield.

## Sources and version boundary

- [Official Armada rules/downloads page](https://www.atomicmassgames.com/swarmadadocs/).
- [Rules Reference Guide 1.6.0](https://cdn.svc.asmodee.net/production-amgcom/uploads/2025/01/SWM_Rules_Reference_Guide_1.6.0_web.pdf), including its core rules, FAQs, and quick reference. Page references below refer to printed pages in this guide.
- Local `swm01_armada_learn_to_play.pdf`, especially pages 7–23.
- Source files and read-only local database queries, plus eight isolated Java probes against the compiled engine. These probes did not modify existing battles.

The published data revision is named `armada_1_5`, whereas the official download is now 1.6.0. Pin a specific rules reference, card errata, point-cost revision, and format for each supported ruleset. A catalogue version name alone does not establish rules compliance. This review covers the core gameplay and expansion mechanisms; it is not a card-by-card certification of every expansion or tournament/campaign rule.

The older Learn to Play booklet has outdated details, including its 300-point standard and older evade behavior. The current reference specifies 400 points as standard. Do not implement older booklet examples where the selected rules revision supersedes them.

Two clarifications to the supplied summary:

- Squadron turns normally activate two squadrons, **one at a time**, or one if that is all that remains. This repeats until the phase is complete; it is not a two-squadron limit for the whole phase.
- Status readies exhausted defense tokens and eligible upgrade cards. It does not indiscriminately discard all spent tokens. Spending an already exhausted defense token discards it during the attack. Flipping the initiative token does not transfer initiative in a standard game.

## 1. What we already have

| Area | Implemented foundation | Qualification |
| --- | --- | --- |
| Catalogue | Versioned definitions, factions, ship/squadron variants, characteristics, generic card/ability objects | Catalogue presence is not executable rules support |
| Fleet creator | Faction/type/variant selection, quantity, derived point totals, muted unaffordable options, saved presets and formations | Costs and fleet legality are not yet official Armada legality |
| Setup | Board dimensions, human/CPU seats, opposite-side placement and deployment preview | Standard deployment sequence and geometry are missing |
| State/API | Authoritative action processing, immutable state transitions, version checks, snapshot/event persistence | Several rule checks remain absent or simplified |
| Round structure | Deployment, Command, Ship, Squadron, Status, round counter | The names exist; activation granularity is wrong |
| Movement | Direct drag/facing edits, reset before ending the player's turn, original-origin range checks | Direct ship movement bypasses maneuver-chart restrictions |
| Maneuvers | Imported speed/yaw profiles, 22.5-degree yaw clicks, deterministic maneuver calculation | Geometry, speed-change timing, and overlap resolution need correction |
| Combat | Hostile-target checks, colored dice pools by range, facing-based arc selection, zone shields, hull damage, destruction | Full attack procedure and dice/token effects are missing |
| Status | Living units become ready; exhausted defense-token counts return to ready | Upgrade refresh, command cleanup, and timing windows are missing |
| UI | Selection, roster, inspector, layer toggles, zoom/pan, trajectories, event log, client replay | Some visible map features are illustrative rather than game objects |
| CPU | Builds/deploys units, chooses attacks/maneuvers, progresses phases | Uses the simplified rules and count-based fleet selection |

## 2. Critical data and deployment defects

### D1 — All published ships currently have one hull point

Read-only queries of `fn_engine_definition_get(4)` returned:

| Projected classification | Variants | Projected hull = 1 | Missing typed defense-token counts | Projected maximum attack range |
| --- | ---: | ---: | ---: | --- |
| Ship | 59 | 59 | 59 | 300 for every variant |
| Squadron | 106 | 0 | 106 | 300 for every variant |

Examples from the raw catalogue versus the runtime projection:

| Ship | Raw hull | Projected hull | Raw command / engineering / squadron |
| --- | ---: | ---: | --- |
| CR90 Corvette A, variant 795 | 4 | 1 | 1 / 2 / 1 |
| Imperial Star Destroyer Cymoon 1 Refit, variant 726 | 11 | 1 | 3 / 4 / 3 |

The projection searches for `Hull Value`, but these ships store `Hull`; the fallback is 1. Squadrons use the other characteristic name. This is a mapping defect, not missing source hull data.

Evidence: `docker/init/sql/025_command_and_defense_token_profiles.sql:57–63`; raw `unit_variant_characteristic` rows for 726 and 795. The same hull-name issue remains in the checked-in projection, so merely applying migration 025 is insufficient.

**Impact:** every ship is much easier to destroy than intended once shields are gone. Current battle outcomes cannot validate Armada balance.

### D2 — The live projection is behind the checked-in command/token migration

The deployed `fn_engine_definition_units(bigint)` does not contain `commandValue` or `defenseTokenCounts`. The raw source has command ratings and named tokens; the Java loader falls back to zero command ratings and generic tokens when these fields are absent.

Migration 025 contains the missing projection fields, but the live function does not match it. Docker mounts SQL under `/docker-entrypoint-initdb.d`; rebuilding the app does not rerun PostgreSQL initialization against an existing data volume.

Evidence: `docker/init/sql/025_command_and_defense_token_profiles.sql:36–56,107–125`, `DefinitionRepository.java:79–106`, `UnitDefinition.java:118–129`, `docker/compose.yaml:12–14`.

**Needed:** an explicit, tracked database migration process and projection contract tests. Existing battle snapshots also retain previously initialized bad resources; correcting the projection alone does not repair historical unit state.

### D3 — Costs are estimates, not official unit costs

`031_unit_variant_points.sql` explicitly derives development costs from hull, shields, speed, and command characteristics. The published definition has 165 variants; the previously reported 330 cost rows cover multiple definition revisions, not 330 distinct units in one playable revision.

**Needed:** canonical, versioned ship/squadron/upgrade costs. Estimated costs affect fleet fairness, squadron caps, initiative bidding, and victory scoring.

## 3. Round and activation model

### R1 — Command Phase is an empty transition

The only legal Command Phase action is `advance_phase`. There is no command-stack state or action for assigning, concealing, revealing, converting, or resolving a dial. Command, Engineering, and Squadron ratings exist in `UnitDefinition`, but are not used to resolve commands.

Missing:

- Secret, simultaneous planning and per-player completion state.
- First-round stacks filled to each ship's command value; later replenishment at the bottom.
- Revealing the top dial when a specific ship activates.
- Dial-to-token conversion, token limits, duplicate-token restrictions, and spending timing.
- Navigate speed/yaw effects; Squadron command activations; Repair shield transfer/recovery and damage-card removal; Concentrate Fire die addition/reroll.
- Dial-plus-token combinations and once-per-round command limits.

Evidence: `AuthoritativeGameEngine.java:117–119,175–183,207–208`; `UnitState.java:6–12`. Rules: pages 3–4, 48.

### R2 — There is an active player, but no active ship or activation substeps

Armada normally activates one ship at a time: reveal command, attack up to twice from different hull zones, then maneuver. METIS uses a single `ready`/`activated` status. An attack sets the ship to `activated`, blocking its required movement and second attack. A move also blocks attacking. The recent editable-movement feature allows revising a move, but does not add the missing activation sequence.

Evidence: `GameState.java:7–10`, `UnitState.java:6–12`, `AuthoritativeGameEngine.moveUnit`, `attackUnit` (especially `522–523,594–597`). Rules: pages 2, 17, 48.

**Needed:** `activeUnitId`, activation step, attacks performed, hull zones used, movement completion, command resolution, and pending response windows. Keep planning/preview separate from committed game effects.

### R3 — Players do not alternate individual ship activations

`endTurn()` advances through player seats once, then moves to the next phase when it wraps to the first seat. It never checks whether ships remain unactivated. A player may act with multiple ships before passing, or pass without acting at all.

An isolated probe confirmed that two `end_turn` actions move a two-player battle to Squadron Phase while **every ship is still ready**.

The button now labelled **End phase** still submits `end_turn`. It passes one player's turn; only the final seat causes a phase transition. Renaming it did not change this engine behavior.

Missing: one-ship alternation, automatic mandatory passes when no eligible ships remain, pass-token rules, and phase completion only after all eligible ships have activated. Rules: pages 14, 17–18. Evidence: `AuthoritativeGameEngine.java:643–672`; `simulator-session.html`, `endPhaseButton` and `end-activation` handler.

### R4 — Initiative is seat order, not the setup decision

The lowest seat always starts. No fleet-point bid, tie resolution, first/second-player choice, or pass-token allocation exists. Standard same-faction battles are also rejected by `validateSetup()`'s duplicate-faction check, even though the rules accommodate them.

Evidence: `AuthoritativeGameEngine.java:57–71,259–262`. Rules: pages 8, 10, 14, 16.

## 4. Attacks, defense, and damage

| Feature | Current behavior | Missing/correction |
| --- | --- | --- |
| Active-player enforcement | Move/end-turn check active player; attack does not | Reject out-of-turn attacks in the engine, independently of UI controls |
| Attack count and order | One attack activates a unit | Two attacks from different hull zones, then maneuver; explicit skipping and completion |
| Attacking/defending zones | Automatically chosen by center-to-center bearing | Player chooses legal attacking and defending hull zones |
| Firing arcs | Four equal angular sectors | Actual printed hull-zone geometry, boundary rules, auxiliary/special arcs |
| Range | Center-to-center distance, fixed default bands 100/200/300 | Hull-zone/base-edge measurements and calibrated range versus distance rulers |
| Dice | Every color rolls `nextInt(6) >= 3`, producing 0 or 1 hit | Armada eight-faced color-specific results, double icons, accuracy, criticals, blanks |
| Misses | Third consecutive zero-hit roll is forced to one hit | Remove this automatic-hit mechanic from standard Armada rules |
| Defense choice | Attack request can name a target token; otherwise first ready type is chosen | Defender-controlled response after attack effects, including choosing not to spend |
| Token effects | Every spent token reduces damage by exactly 1 | Brace, redirect, evade, scatter, contain, and salvo effects and interactions |
| Token lifecycle | Ready to exhausted; one type spent per attack | Multiple different types, spent-exhausted discard, token identity, accuracy locks, speed-zero restrictions |
| Damage | Zone shields followed by integer hull reduction | Damage deck, faceup/facedown cards, critical effects and persistent card effects |
| Anti-squadron ship attacks | One target in a single action | Additional squadron targets in the same attacking hull zone's attack sequence |
| Line of sight/obstruction | No runtime check | Targeting points, intervening hull zones, obstacle/ship obstruction and die removal |

Evidence: `AuthoritativeGameEngine.java:501–640,680–694,816–901`; `AttackDie.java`; `UnitDefinition.java`. Rules: pages 2–6, 10–11, 13.

The out-of-turn attack, move-blocks-attack, and attack-blocks-movement findings were reproduced using the compiled engine. These are not just UI omissions.

## 5. Movement and physical geometry

Two incompatible movement paths currently exist:

1. **Maneuver actions:** speed/yaw chart validation, sampled circular-base collision checks, and board containment.
2. **Direct board actions:** a distance limit and point-in-board check, plus unrestricted supplied facing.

The direct path permits sideways/backward ship movement, arbitrary rotation, and placement directly on another unit. The maneuver path rejects crossing another unit anywhere along its sampled path, whereas Armada generally resolves overlaps at the final position; moving through units is allowed.

Missing or incorrect:

- Proper base rectangles and size classes; ships currently use a shared radius, not their true footprint.
- Maneuver-tool placement on either side, base-notch offsets, final overlap with the tool, and accurate endpoint geometry.
- Speed maintained until changed by Navigate or another effect. Current maneuver actions can choose any available speed without paying the relevant command/effect.
- Distinct squadron movement using its printed speed/distance ruler rather than ship yaw charts.
- Ship overlap resolution: temporarily reduce maneuver speed and deal collision damage; flotilla exceptions.
- Relocation of squadrons overlapped by ships.
- Destruction when a final base position leaves the play area, instead of merely rejecting/clamping the move.
- Correct speed-zero behavior. The direct UI treats zero as speed 1; the engine's direct path can fall back to another nonzero distance.

Movement editing/reset is useful UX, but currently writes real unit positions immediately and can remain available while other friendly units act. Full rules support should use a tentative maneuver for the active ship and commit at the correct timing boundary, before movement-triggered effects or another activation. Dragging and a heading handle can remain, but should select legal maneuver outcomes rather than bypass the chart.

Evidence: `AuthoritativeGameEngine.java:321–434`, `MovementCalculator.java:13–46`, `UnitDefinition.java:140–152`; `simulator-session.html`, `movementOrigin`, `movementLimit`, `validMoveTarget`, `submitBoardMove`. Rules: pages 5, 11, 14, 17–20, 27.

## 6. Squadrons

The engine distinguishes ships from squadrons for normal phase eligibility and chooses anti-squadron versus battery dice. The one-action status gives a basic move-or-attack restriction. The rest is incomplete:

- No alternating two-squadron activation groups; the generic player-turn model is reused.
- No Squadron command activation during Ship Phase, or move-and-attack entitlement for that activation.
- All newly deployed units start at speed 1, including squadrons whose printed speeds differ.
- No engagement detection, movement lock, or requirement to attack squadrons when engaged.
- No execution of Bomber, Escort, Swarm, Counter, Heavy, Rogue, Grit, Intel, Relay, Snipe, Cloak, Strategic, Adept, AI, Dodge, Assault, Scout, Screen, or named-pilot abilities.
- No proper distance-1 attack cap. Runtime profiles give all squadrons maximum range 300 and apply ship-style color/range filtering. A probe confirmed a squadron with a red battery attacking a ship at 250 board units.
- Squadron orientation should not restrict its normal attack direction. Runtime battery selection partly accommodates this, but the UI can still draw ship-style front arcs for squadrons.

Evidence: `AuthoritativeGameEngine.unitCanActInPhase`, `defaultSpeed`, `attackPlan`; `CpuBattleRunner.moveAction`; `UnitDefinition` has no keyword/ability fields. Rules: pages 6, 19–20, 28.

## 7. Fleet construction and setup

| Area | Current state | Needed |
| --- | --- | --- |
| Budget | UI checks plus server-side recalculation for saved presets | Canonical costs and a common server validator for presets, battle fleets, CPU fleets, and starting a battle |
| Runtime fleet APIs | Accept stored/client points totals; unit creation validates game membership | Recalculate totals and enforce legality before deployment/start, independent of the UI |
| Unique units | Repeated variants allowed | Unique-name and unique-squadron-type restrictions; duplicate Ciena Ree is a visible example |
| Squadrons | Generic format count options, mostly used by CPU | Standard squadron budget: at most one third of agreed points, rounded up; ace/defense-token cap for the selected rules version |
| Commander | Setup has a player called Commander | Actual commander upgrade, flagship assignment, and restrictions |
| Upgrades | Catalogue data exists | Equipping upgrades, slots, multiple-icon costs, faction/size/title/modification/trait restrictions, costs, effects |
| Objectives | Scenario selector and catalogue objects | Three fleet objectives, first player choosing from the second player's objectives, objective-specific setup |
| Obstacles | Four fixed illustrative shapes | Alternating placement of the standard obstacle set, geometry and spacing validation |
| Deployment | Saved formations bulk-deployed; CPU auto-places | Alternating one ship/two squadrons, selected starting ship speed, squadron proximity to friendly ships |
| Deployment zones | Left/right board-percentage strips | Standard setup area and long-edge deployment, or explicitly selected short-edge objective exceptions |
| Same-faction play | Engine rejects repeated factions | Distinguish ownership/teams from faction identity |

For a displayed 1800-by-900 board, current placement uses the left/right short edges. Standard setup seats players along the long edges and limits deployment within a central setup area. A short-edge scenario is possible in Armada, but the current implementation does not derive that choice from an objective.

The engine's deployment action itself checks board bounds, not a player's deployment zone, overlap legality, deployment order, fleet entitlement, or squadron proximity.

Evidence: `FleetPresetRepository.java:33–113`; `BattleApiController.java:178–252,377–405`; `docker/init/sql/009_runtime_resource_routines.sql:112–205`; `031_unit_variant_points.sql`; `simulator-game.html`; `CpuBattleRunner.java:168–249`. Rules: pages 6–8, 14, 16, 18, 21–22.

## 8. Objectives, terrain, victory, and status

### Scenario content is not driving the board

`BoardState` carries only a code and dimensions. Session mode/scenario IDs are stored, but start constructs the engine setup from the request's board and scalar options. There is no loaded obstacle/objective runtime model or rule executor.

`renderTacticalOverlay()` draws the same fixed terrain ellipses and OBJ A/B/C positions. They do not cause obstruction, damage, repairs, capture, scoring, or objective effects.

Needed: typed placed objects, scenario setup actions, station/asteroid/debris effects, objective victory tokens and scoring/timing, and additional obstacle behavior where supported.

### End conditions are not standard Armada scoring

- Victory currently requires only one player to retain any living unit, including squadrons.
- Standard rules end when a fleet loses all its ships, ignoring remaining squadrons. Tournament formats have additional timing/eligibility rules that need their own format implementation.
- Reaching the configured round limit always records a draw, rather than comparing destroyed-unit/upgrade costs and objective points, with the correct tie-break.
- Unlimited mode removes the round limit but retains the generic survival rule and fixed first seat. The reference's optional unlimited-round rules also change initiative between rounds.

### Status is only partly implemented

Ready/exhausted defense-token counters and living-unit readiness reset exist. Missing upgrade-card refresh/ready costs, temporary-effect cleanup, full command-dial/token cleanup, objective end-of-round timing, and standard scoring. Commands, repair, card effects, and token discard rules need separate handling; Status should not simply restore everything.

Evidence: `BoardState.java`; `BattleApiController.startSession`; `AuthoritativeGameEngine.java:210–237,601–633`; `simulator-session.html:415–466`; `021_armada_game_modes.sql`. Rules: pages 12–16, 20–22, 45, 48.

## 9. Expansion mechanisms and hidden information

Full catalogue coverage requires more than adding stat values:

- Huge ships: six hull zones, auxiliary arcs, three attacks, special deployment/movement and scoring.
- Flotilla collision and commander restrictions.
- Ignition attacks, special batteries/arcs, targeting tokens, and extreme range.
- Salvo/counter response attacks and timing.
- Raid, proximity mines, grav, focus/chaff, objective and other card-specific tokens.
- Set-aside ships/squadrons and later deployment effects.
- Upgrade exhaustion/discard/recovery, ready costs, command tokens/dials stored on cards, and a timing/effect system.
- Optional campaign progression, resources, damage/scarring/veterans, team play, and format-specific rules. A Campaign Battle dropdown does not implement a campaign.

For genuine two-human play, session-owner editing and viewer invitations are not enough. Human seats currently have no authenticated seat-owner mapping in the engine contract. Full state is returned to authorized viewers. Hidden command stacks and damage/deck information will require player-specific views, protected events/logs, and defender-specific input windows. The present state contains no secret command information, so this is a missing gameplay capability rather than a claim that existing secret dials are leaking.

Evidence: `UnitState`, `UnitDefinition`, `GameState`; `WebAuthInterceptor.java:50–62`; `BattleApiController.state`; `026_user_sessions_and_invitations.sql:115–138`; generic catalogue objects are not loaded as executable effects by `DefinitionRepository`.

## 10. UI/CPU inconsistencies to correct alongside rules

- Ship Phase is labelled **Movement**, even though a real ship activation includes commands and attacks.
- **End phase** still means the existing player-wide `end_turn`; activation completion and phase completion need distinct semantics.
- **MIN SPEED** displays `currentSpeed`, not a minimum.
- The maneuver-speed change handler calls a renderer that reselects `unit.currentSpeed`, so changing the dropdown can revert the selection.
- Range rings use the same projected maximum 300 for every unit. They do not show per-arc/color attack reach or proper squadron distance-1 reach.
- The smaller turning circle is decorative, not a legal turning envelope.
- Ship art is chosen from generic shapes using a unit-code hash; neither silhouettes nor marker dimensions establish physical base geometry.
- Legal attack targets are combined into a global set, rather than associated with the selected attacker and hull zone. A target available to one unit can misleadingly appear available for another.
- The CPU advances Command and Status automatically in mixed battles, which must change when these phases contain human decisions.
- CPU fleet selection uses count limits and random profile choice, not official points, uniqueness, commander/upgrades, or squadron caps.
- The CPU prefers an attack instead of movement for a ready unit. It does not execute a full ship activation, and rejected movement can cause it to pass the whole player turn.
- Human drag movement and CPU maneuver movement are validated differently, giving them different effective movement capabilities.

Evidence: `simulator-session.html`, `phaseLabel`, `renderMovementCommand`, `syncSelectedActions`, `renderLegalActions`, `unitArtwork`; `CpuBattleRunner.java:86–92,129–165,197–249,284–316`.

## 11. Validation findings and test gaps

Eight isolated probes confirmed the current engine behavior:

1. Command Phase exposes only `advance_phase`.
2. Moving a ship blocks its attack.
3. Attacking blocks that ship's maneuver.
4. A non-active player can submit an accepted attack.
5. Two player passes advance the phase with all ships unactivated.
6. A squadron can attack a ship at 250 board units with red battery dice.
7. Losing the last ship does not end the battle if a squadron survives.
8. A direct move can end exactly on another ship with arbitrary facing.

Existing tests are useful for state immutability, versioned profiles, movement/reset bounds, simplified combat, and repeatable CPU play. Some tests deliberately encode the simplified model, including ending on the last opposing **unit** and rejecting passage across another base. A green test suite therefore does not establish Armada compliance.

Add rules-conformance cases for:

- Correct raw-to-runtime values for representative ships/squadrons and every deployed definition revision.
- Alternation with unequal fleet sizes, pass tokens, exhausted fleets, and activation completion.
- Hidden command-stack replenishment, reveal order, token conversion, all four commands and response windows.
- Two distinct hull-zone attacks followed by movement; flak target sequences; out-of-turn attack rejection.
- Exact colored dice faces, accuracy locks, defender choices, token discard, critical damage cards.
- Legal maneuver endpoints, side placement, speed changes, speed zero, overlaps/ramming, and leaving the board.
- Squad activation pairs, printed-speed movement, engagement, distance-1 attacks, commands and keywords.
- Canonical fleet costs, uniqueness, squadron/ace caps, upgrades, objectives, initiative and deployment.
- Six-round scores, objective points, last-ship victory, tie-breaks, and mode exceptions.
- Browser interactions using the same legal geometry as the engine; replay reproducing committed outcomes and movement edits.

## 12. Recommended implementation order

### Priority 0 — Establish trustworthy data and stop invalid transitions

1. Fix `Hull`/`Hull Value` mapping and verify all required profile fields; apply missing command/token projection through tracked migrations.
2. Add golden catalogue projection tests and pin rules/errata/point-cost versions.
3. Enforce active-player checks for attacks.
4. Decide how existing bad snapshots are handled without silently changing recorded battle history.

### Priority 1 — Correct the core activation and command model

1. Add an active ship/squadron and explicit activation steps.
2. Alternate one ship or two squadrons correctly; advance phases only when all eligible activations are finished.
3. Implement command stacks, concealed player views, reveal/conversion, and command effects.
4. Retain movement editing as a legal planning interface with an explicit commit point; distinguish ending an activation from ending the phase.

### Priority 2 — Make combat and movement rules-faithful

1. Implement colored dice results, attack substeps, defender responses, and damage cards.
2. Unify drag and CPU maneuver validation with physical base/hull-zone geometry.
3. Implement correct overlaps, obstruction, squadron movement/engagement, and basic keywords.
4. Add conformance tests before expanding the CPU's tactical behavior.

### Priority 3 — Complete standard fleet setup and game results

1. Canonical costs, full fleet legality, commander/upgrades, and initiative selection.
2. Objective choice, obstacle placement, alternating deployment and starting-speed selection.
3. Objective effects, standard victory/scoring, status cleanup and upgrade refresh.
4. CPU fleets and activations using the same rules and validators as human players.

### Priority 4 — Extend supported content deliberately

Implement card effects/keywords, special ships and tokens, remaining objectives, tournament-format exceptions, then campaign/team variants with a documented support matrix.

The next substantial feature should be the **activation state machine**, preceded by the profile/projection corrections. More controls on the current player-wide move-or-attack loop would otherwise preserve the main rules mismatch.

## Code-reference index

- [Authoritative engine](engine-core/src/main/java/com/metis/engine/core/AuthoritativeGameEngine.java)
- [Unit runtime state](engine-core/src/main/java/com/metis/engine/core/UnitState.java) and [game runtime state](engine-core/src/main/java/com/metis/engine/core/GameState.java)
- [Unit definition](engine-core/src/main/java/com/metis/engine/core/UnitDefinition.java) and [movement calculator](engine-core/src/main/java/com/metis/engine/core/MovementCalculator.java)
- [Definition loader](engine-api/src/main/java/com/metis/engine/api/DefinitionRepository.java)
- [Battle API](engine-api/src/main/java/com/metis/engine/api/BattleApiController.java)
- [CPU runner](engine-api/src/main/java/com/metis/engine/api/CpuBattleRunner.java)
- [Preset validator](engine-api/src/main/java/com/metis/engine/api/FleetPresetRepository.java)
- [Fleet/setup UI](webapp/src/main/resources/templates/simulator-game.html)
- [Battle UI and replay](webapp/src/main/resources/templates/simulator-session.html)
- [Command/token projection](docker/init/sql/025_command_and_defense_token_profiles.sql)
- [Development point costs](docker/init/sql/031_unit_variant_points.sql)
- [Existing engine tests](engine-core/src/test/java/com/metis/engine/core/AuthoritativeGameEngineTest.java)
- [Previous data-gap analysis](ENGINE_DATA_GAP_ANALYSIS.md): useful background, but its points/deployment/arc-completeness statements need reconciliation with this review and the live database.
