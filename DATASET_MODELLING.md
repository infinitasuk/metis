# METIS Dataset Modelling Backlog

This document identifies the datasets METIS needs to model as it grows from a
read-only catalogue into a generic tabletop game engine. A dataset is a
logical body of information; it does not necessarily map to one database
table. The physical schema should follow the decisions recorded here.

## Scope

Armada is the first source dataset, but the model must remain usable for other
tabletop games. Keep these concerns separate:

- Static definitions describe what a game contains and how it normally works.
- Runtime state describes one particular played match.
- Rules data supplies parameters; executable Java code applies behaviour.
- AI data evaluates legal actions; it does not define legality.

## Current Baseline

The current PostgreSQL seed contains the following Armada reference data:

| Dataset | Schema | Current coverage | Status |
| --- | --- | ---: | --- |
| Game | `game_engine` | 1 | Seeded |
| Factions | `game_engine` | 4 | Seeded |
| Unit classifications | `game_engine` | 8 | Seeded |
| Unit types | `game_engine` | 66 | Seeded |
| Unit variants | `game_engine` | 165 | Seeded |
| Characteristic types | `game_engine` | 22 | Seeded from imported names |
| Unit type characteristics | `game_engine` | 0 | Not populated by importer |
| Unit variant characteristics | `game_engine` | 1,745 | Seeded as typed JSONB |
| Terrain types | `game_engine` | 4 | Placeholder reference data |
| Game rules | `game_engine` | 6 | Reference-only placeholders |
| Game modes | `game_engine` | 2 | Reference modes with phase/scenario links |
| Game mode rules | `game_engine` | 12 | Six reference rules linked to each mode |
| Source/version metadata | `game_engine` | 1 source, 1 version | Seeded with BSData provenance |
| Generic object types | `game_engine` | 34 | Seeded for cards, profiles, tokens, dice, dials, bases and rule components |
| Generic game objects | `game_engine` | 1,476 | Upgrade/objective/unit cards and derived reusable objects |
| Object eligibility and associations | `game_engine` | 591 / 1,299 | Faction eligibility plus unit, mode and scenario links |
| Phases/actions | `game_engine` | 4 / 5 | Static structure; execution remains in Java |
| Scenarios/boards | `game_engine` | 1 / 1 | Standard reference scenario and 6 ft x 3 ft board |
| Fleet formats | `game_engine` | 2 | 400-point reference format per mode |

The `game_engine` schema owns static definitions and configuration. The
`game_instance` schema is reserved for user-owned game instances, runtime state,
actions, events and results. It currently contains no runtime tables because
that model is still being designed; `game_engine.game_mode` is a mode-definition
catalogue, not a played game instance.

The importer is `tools/generate_armada_seed.ps1`. Its source is the
community-maintained BSData Armada catalogue, including January 2025 errata
updates. Characteristic JSON currently retains `raw_value` and source
metadata, but the engine semantics are not yet defined.

## Priority 0: Catalogue Foundations

### 1. Source and catalogue metadata

This dataset identifies where a definition came from and which version is
valid.

Examples:

- Game-system and catalogue name.
- Source repository, URL, licence, import timestamp and source revision.
- Errata or rules edition and effective date.
- Stable external IDs and aliases for imported records.
- Import status, warnings and rejected records.

Current state: `content_source` and `game_version` provide source provenance for
the Armada import. Individual generic objects also retain stable catalogue and
profile IDs in `source_reference_json`.

Questions to settle:

- Is a catalogue revision immutable once imported?
- Can multiple revisions of the same game be active at once?
- Which identifier is used when an upstream source renames a unit?
- How are corrections made without losing the original source record?

### 2. Unit profiles and characteristic semantics

This dataset describes the static properties of a unit type or variant.

Armada examples include hull, shields, speed, command, engineering, firing
arcs, attack dice, defence tokens, upgrade bars, keywords and squadron
abilities. Other games may need wounds, armour locations, heat, ammunition,
movement values or model equipment.

Current state: Armada variant characteristics are stored as typed JSONB. The
current importer recognises numbers, dice, tokens, upgrade slots, keywords and
free text. Unit type characteristics are not populated, and inheritance is
not yet applied by the application.

Questions to settle:

- Which characteristics are shared at type level and which belong to variants?
- What is the effective-value and override/merge rule for JSON objects and
  arrays?
- Which values need engine-readable vocabularies rather than free text?
- Which physical properties are needed for movement, range, line of sight and
  collision calculations?

### 3. Upgrades, equipment and loadouts

This dataset represents things selected onto a unit rather than the unit's
base profile.

Examples:

- Armada upgrade cards and upgrade types.
- Cost, card text, unique names and restrictions.
- Faction, unit, classification and slot eligibility.
- Modifiers, granted abilities and replacement characteristics.
- Titles, commanders, officers, weapons and other attachable content.

Current state: upgrade profiles are imported as generic `game_object` rows with
profile characteristics, source IDs, faction eligibility, and any parsed source
constraints/costs retained in JSON. They are not executable effects yet.

Questions to settle:

- Is card text reference-only, executable, or both?
- How are additive, replacing, conditional and once-per-round effects
  represented?
- Are loadouts static definitions, a player selection, or both?

### 4. Fleet and army construction legality

This dataset defines which combinations of units and upgrades are legal before
a match starts.

Examples:

- Point limits and fleet/army size.
- Required and optional commanders or leaders.
- Ship, squadron, model and upgrade limits.
- Faction and alliance restrictions.
- Unique-name limits and duplicate restrictions.
- Edition, format and source legality.

Current state: `fleet_format`, `fleet_format_faction`, `fleet_unit_limit` and
`game_object_eligibility` provide the initial static legality boundary. Source
constraints are retained on imported objects; validation behaviour is not
implemented yet.

Questions to settle:

- Are legality rules part of a game edition, a match format, or a source
  catalogue?
- Does validation return only valid/invalid, or explain every violation?
- Can the same unit definition have different costs or restrictions by format?

## Priority 1: Rules and Game Modes

### 5. Rules, phases and actions

This dataset describes the rule parameters and structure used by executable
game logic.

Examples:

- Round and phase order.
- Initiative and turn order.
- Available actions and action costs.
- Movement and manoeuvre parameters.
- Range, firing arc, line-of-sight and attack rules.
- Dice, defence, damage and repair rules.
- Keywords, conditions, triggers and effects.
- Deployment, scoring and victory conditions.

Current state: `game_rule` contains six Armada headings, while
`phase_definition`, `game_mode_phase` and `action_definition` provide the first
structured rule vocabulary. These remain reference data, not executable rules.

Questions to settle:

- Which rules are data and which rules must be Java implementations?
- How are timing windows, priorities, interrupts and replacement effects
  represented?
- How is randomness represented so that a result can be reproduced?
- Can a rule reference a unit characteristic, terrain property or scenario
  setting by stable ID?

### 6. Game modes and scenarios

This dataset defines the configuration for a type of match, not a played match
instance.

Examples:

- Standard Battle and other formats.
- Points limit, round count and player count.
- Setup and deployment zones.
- Objectives, scoring and winning conditions.
- Allowed factions, units, upgrades and terrain.
- Scenario-specific rule modifiers.

Current state: `game_mode` contains `Standard Battle` and `Fleet Battle`, each
with four ordered phases, a fleet format and a scenario link. Assault, defense
and navigation objectives are imported as generic objects and linked to the
standard scenario pool.

Questions to settle:

- Is a game mode a format, a scenario, or a composition of both?
- Are objectives reusable definitions with scenario-specific parameters?
- How are setup instructions represented for both humans and the engine?

### 7. Terrain, board and spatial definitions

This dataset describes the physical or logical space in which a match occurs.

Examples:

- Board dimensions and coordinate system.
- Terrain and obstacle geometry.
- Deployment zones and restricted areas.
- Objective zones and measurement points.
- Base geometry, firing arcs and range bands.
- Rules-relevant terrain properties such as obstruction or movement cost.

Current state: four Armada terrain names remain reference terrain rows. A
`board_definition` and `scenario_board` provide the standard 6 ft x 3 ft board
boundary; detailed obstacle geometry and deployment zones are still absent.

Questions to settle:

- How can continuous 2D, square-grid and hex-grid games share the model?
- Is geometry stored in PostgreSQL/PostGIS, JSON, or an engine-side format?
- Which spatial facts belong to the board and which effects belong to rules?
- Can a scenario provide reusable board layouts without copying terrain data?

## Priority 2: Runtime and Supporting Datasets

### 8. Runtime match state

This dataset records what is happening during one played match. It must not be
mixed with static catalogue definitions.

Examples:

- Match instance, players and faction ownership.
- Chosen fleet/army and instantiated units.
- Position, heading, facing and base geometry.
- Current hull, shields, damage, heat, ammunition and resources.
- Tokens, statuses, conditions, activation state and command state.
- Current round, phase, initiative and pending decisions.
- Event, action and state-change history.

Current state: deliberately absent from the initial schema. The DDL comment
explicitly limits the database to static game definitions.

Questions to settle:

- Is the authoritative state event-sourced, snapshot-based, or hybrid?
- Which state changes must be auditable and replayable?
- How are hidden information and simultaneous decisions handled?
- How does computer vision submit observations without directly mutating
  trusted game state?

### 9. Objectives, statuses, abilities and relationships

These cross-cutting datasets may become shared definitions or remain
game-specific, depending on the rules model.

Examples:

- Objective cards and objective zones.
- Status effects and condition durations.
- Abilities, triggers and granted actions.
- Parent/child, squadron, transport, escort and attachment relationships.
- Replacement, upgrade and dependency relationships between definitions.

Current state: keywords and squadron abilities are mostly free-text or small
JSON objects inside characteristics. No reusable relationship model exists.

Questions to settle:

- Which concepts need stable identities for rule references?
- Can a generic relationship model cover transport, attachment and command
  structures without game-specific columns?
- When is free text sufficient for catalogue browsing but insufficient for the
  rules engine?

### 10. AI and evaluation data

This dataset supports choosing between legal actions. It should not duplicate
the rules or static unit definitions.

Examples:

- Evaluation weights and difficulty profiles.
- Objective priorities and risk tolerance.
- Search depth and planning horizon.
- Tactical tags or training annotations.
- Decision explanations and evaluation traces.

Current state: absent. AI should first consume legal actions and simulated
states produced by the rules and runtime layers.

## Explicitly Out Of Scope For The First Catalogue Model

The following should not be forced into the current static schema:

- A complete runtime game state.
- Camera detections and raw computer-vision frames.
- AI decisions or evaluation traces.
- Fully executable rule scripts stored as arbitrary database text.
- A universal table for every game's specialised mechanics.

## Recommended Modelling Order

1. Freeze source/version identity and stable external IDs.
2. Define characteristic semantics, inheritance and validation.
3. Model upgrades and fleet-construction legality.
4. Define the rule, phase, action and effect boundary.
5. Replace match and terrain placeholders with scenario and spatial concepts.
6. Add runtime state only after static definitions can create a valid match.
7. Add AI data after legal action generation and state simulation exist.

Each dataset should have a clear owner, stable identity, provenance, versioning
rules, validation rules, and at least one concrete Armada example before it is
considered modelled.
