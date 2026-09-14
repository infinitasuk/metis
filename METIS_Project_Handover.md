# Project METIS

**METIS --- Modular Engine for Tabletop Intelligence & Strategy**

## Project vision

METIS is a generic tabletop/board-game engine with AI-controlled
opponents rather than an AI implementation for one specific game.

The original inspiration is **Star Wars: Armada**: a physical tabletop
setup where a human can play against an AI opponent. The eventual system
could use a physical tabletop and miniatures, an overhead camera to
detect pieces and movement, and a projector to display overlays and
indicate the AI's chosen moves. The human moves their pieces, computer
vision updates METIS's internal game state, the AI determines a
response, and the projector shows where AI-controlled pieces should be
moved.

The longer-term goal is:

> Build a modular engine capable of supporting many different tabletop
> games by loading game-specific definitions, rules, units and
> characteristics into a common engine.

Potential test games discussed include Star Wars: Armada, Warhammer
40,000, BattleTech, Star Wars: Legion, X-Wing, Blood Bowl, Gloomhaven,
HeroQuest, Twilight Imperium and Bolt Action.

Armada is expected to be the first implementation, but the underlying
database and engine must not be designed specifically around Armada.

## Design philosophy

The project owner wants to design the system themselves. Assistance
should primarily act as a sounding board: pressure-test ideas, identify
implications and edge cases, answer technical questions, and compare
proposed concepts against different tabletop games. Avoid continually
redesigning the architecture or jumping several stages ahead unless
alternatives are explicitly requested.

## Static definitions vs game state

A major distinction is between **static game definition** and **runtime
game state**.

Static definitions describe what can exist and how it normally behaves:
games, factions, unit types, unit variants, characteristics, terrain
types, game rules, match definitions and victory conditions.

Runtime state describes what is happening in a particular played game:
current positions, health, shields, speed, injuries, statuses, objective
ownership, score, round and phase.

For example, a Blood Bowl player receiving an injury during play is game
state. The static model may define that injuries are possible or
mutable, but should not store that particular runtime injury.

## Current database model

The initial model contained:

``` text
Game
Faction
Unit Type
Unit
Game Rule
```

It has evolved approximately into:

``` text
game_engine
│
├── game
│   └── faction
│       └── unit_classification
│           └── unit_type
│               ├── unit_type_characteristic
│               └── unit_variant
│                   └── unit_variant_characteristic
│
├── terrain_type
├── game_rule
├── game_mode (static definition)
├── game_mode_rule
├── content_source
├── game_version
├── phase_definition / action_definition
├── scenario_definition / board_definition
├── fleet_format / fleet_unit_limit
└── generic game_object types, objects and associations

game_instance
└── runtime match tables (not yet designed)
```

There is also:

``` text
game_engine.characteristic_type
        ├── game_engine.unit_type_characteristic
        └── game_engine.unit_variant_characteristic
```

Mode-specific structure is stored in `game_engine.game_mode.mode_config_json`.
The `game_engine.game_mode_rule` relationship identifies which reusable rules
are enabled by each mode and allows mode-specific rule configuration.

### Database schemas

The database is split into two ownership boundaries:

- `game_engine` contains versioned static game definitions, catalogue content,
  rules and scenario configuration.
- `game_instance` contains user game instances, selected forces, runtime state,
  actions, events and results.

The current `game_engine.game_mode` table is a static mode-definition catalogue.
It is not the runtime record of a user's played game. The `game_instance` schema
has been created, but its tables remain intentionally deferred until the
runtime model is agreed.

## Game

`game` represents each game supported by METIS, for example Star Wars:
Armada, Warhammer 40,000, BattleTech or Gloomhaven.

The current ERD also includes game-level constraints such as:

``` text
minimum_number_of_players
maximum_number_of_players
minimum_number_of_cpu
maximum_number_of_cpu
```

## Faction

A game has factions. Armada examples include Galactic Empire, Rebel
Alliance, Galactic Republic and Separatist Alliance. Warhammer 40K
examples include Space Marines, Orks and Forces of Chaos.

Conceptually:

``` text
Game
  ↓
Faction
```

## Unit hierarchy

The hierarchy is:

``` text
Faction
  ↓
Unit Classification
  ↓
Unit Type
  ↓
Unit Variant
```

These levels are deliberately generic rather than reproducing the
terminology of one game.

### Armada example

A key decision is:

> TIE is the type; Fighter, Bomber, etc. are variants.

Therefore:

``` text
Faction: Galactic Empire

Classification: Fighter/Squadron
    ↓
Type: TIE
    ↓
Variants:
    Fighter
    Interceptor
    Bomber
    Advanced
    Defender
    Phantom
```

Do not treat TIE Fighter and TIE Bomber as separate unit types under the
current design.

Another conceptual example is:

``` text
Classification: Ship
    ↓
Type: Star Destroyer
    ↓
Variants:
    Imperial-class
    Victory-class
    Gladiator-class
    Onager-class
```

### Warhammer 40K test

40K was used to test the hierarchy outside Armada:

``` text
Faction: Space Marines

Classification: Vehicle
    ↓
Type: Dreadnought
    ↓
Variants:
    Redemptor
    Ballistus
    Brutalis
```

or:

``` text
Classification: Vehicle
    ↓
Type: Land Raider
    ↓
Variants:
    Land Raider
    Crusader
    Redeemer
```

These are modelling tests rather than a final canonical 40K dataset.

## Characteristics system

Different games have radically different unit properties.

Armada may care about movement, hull, shields, firepower, firing arcs,
command, squadron and engineering. 40K may care about movement,
toughness, save, wounds, leadership and objective control. BattleTech
could introduce armour locations, internal structure, heat, weapons and
ammunition.

The schema should not need hundreds of nullable columns to support these
differences.

An early idea used separate JSON columns such as `movement_json`,
`firepower_json`, `skills_json` and `injuries_json`. This evolved into a
data-driven characteristic model.

## Characteristic type

`characteristic_type` identifies categories of characteristics.

``` text
characteristic_type
-------------------
characteristic_type_id
characteristic_type_name
characteristic_type_description
```

Possible records include Movement, Firepower, Defence, Skills, Armour,
Shields, Damage, Transport and Heat.

This allows new games to introduce characteristic categories without
altering the physical table schema.

## Unit type characteristics

A unit type can have multiple characteristic rows:

``` text
unit_type_characteristic
------------------------
unit_type_characteristic_id
unit_type_id
characteristic_type_id
characteristic_data
```

For example:

``` text
TIE | Movement | { JSON }
TIE | Defence  | { JSON }
TIE | Skills   | { JSON }
```

The JSON structure is specific to the characteristic.

Example movement data:

``` json
{
  "maximumSpeed": 4,
  "minimumSpeed": 1,
  "turnRate": 2
}
```

Example shields data:

``` json
{
  "front": 4,
  "left": 3,
  "right": 3,
  "rear": 2
}
```

## Unit variant characteristics and inheritance

Variants can also have characteristic rows:

``` text
unit_variant_characteristic
---------------------------
unit_variant_characteristic_id
unit_variant_id
characteristic_type_id
characteristic_data
```

The established principle is:

> Define behaviour at the broadest applicable level and only store
> exceptions further down.

For example:

``` text
TIE
├── Movement = {...}
├── Defence = {...}
└── Skills = {...}

TIE Bomber
├── Firepower = {...}
└── Movement = {...}
```

The TIE Bomber inherits TIE characteristics except where it supplies an
override.

Conceptually:

``` text
effective characteristics =
    type characteristics
    + variant overrides
```

For an individual category:

``` text
effective movement =
    variant movement if defined
    otherwise type movement
```

## Database technology

SQL Server was initially considered. It would normally store JSON in
`NVARCHAR(MAX)` with `ISJSON()` validation.

Because METIS is intended to become a proper distributable/self-hosted
application and eventually be Dockerised, **PostgreSQL** became the
preferred database.

Reasons include:

-   Free/open source
-   Excellent Docker support
-   Strong Java/Spring support
-   No SQL Server licensing concerns for self-hosters
-   Native JSON support
-   Strong `JSONB` functionality

Characteristics should probably use PostgreSQL `JSONB`.

### JSON vs JSONB

`JSON` largely stores the supplied JSON representation as text.

`JSONB` parses the JSON into PostgreSQL's internal representation and
supports efficient querying, indexing and containment operations.

METIS characteristics are application data, so `JSONB` is the current
preference:

``` sql
characteristic_data JSONB NOT NULL
```

## Terrain

`terrain_type` defines the terrain types that can exist in a game.

Armada examples might include Asteroid Field, Debris Field, Station and
Dust Field. 40K examples might include Ruins, Woods, Crater and
Barricade.

Terrain can define its normal/default behaviour, while unit
characteristics describe exceptions.

For example:

``` text
Terrain: Difficult Ground

Default:
{
    "movement_penalty": 2
}
```

Most units use that default. A unit that ignores difficult terrain
overrides it through its characteristics.

This gives an inheritance concept such as:

``` text
Terrain Type default
        ↓
Unit Type override
        ↓
Unit Variant override
```

The broader principle remains: store defaults at the broadest applicable
level and exceptions lower down.

## Game rules

`game_rule` belongs directly to `game`.

Potential rule areas include turn structure, movement, combat,
deployment, initiative, scoring, victory conditions, terrain rules and
special rules.

There is an important distinction between human-readable rule
documentation and structured information the engine can execute. This
area remains only partially designed: `phase_definition`,
`game_mode_phase`, `action_definition` and the generic `game_object` model now
store the static vocabulary, while Java execution semantics are still pending.

## Game modes

A `game_mode` table describes a configured way to play a game rather than an
individual runtime game instance.

Potential examples include Open Play, Narrative Play, Matched Play, Standard
Battle, Skirmish, Objective Battle, Escort, Capture Points and Annihilation.

Mode-level configuration includes board size, points limit, round count, phase
order, deployment rules, objectives, winning conditions and scoring. Flexible
mode-specific structure belongs in `mode_config_json`, while reusable rules
are linked through `game_mode_rule`.

The current Armada seed demonstrates this structure with Standard Battle and
Fleet Battle modes, a four-phase reference sequence, a standard scenario,
objective-card pools, a standard board boundary and reference fleet formats.
These definitions are still reference-only and do not yet drive executable
rules.

## Generic game objects

`game_object_type` and `game_object` represent static content that is not
specific to the unit hierarchy. Armada upgrade profiles and objective profiles
are imported here with their source IDs, typed profile characteristics, source
constraints and source costs retained in JSON. Unit cards, attack profiles,
movement profiles, squadron abilities, keywords, tokens and attack dice are
also addressable as generic objects.

Use `game_object_eligibility` for faction or unit legality and the association
tables for unit, mode and scenario membership. Do not create a new table for
each card subtype; add an object type and an importer mapping instead.

## Other static concepts identified

Potential future areas include:

-   Objectives
-   Board/map definitions
-   Round/phase definitions
-   Actions
-   Status effects
-   Weapons/attacks
-   Abilities/skills
-   Resources
-   Deployment rules
-   Turn order/initiative
-   Scoring rules
-   Dice/randomness rules
-   Unit relationships
-   Restrictions
-   Environmental rules

These are areas to consider, not an agreed list of tables.

## Board representation

The physical/logical board should be a **generic spatial model**.

It should answer spatial questions such as:

-   Where is this unit?
-   What objects are nearby?
-   Does this path intersect terrain?
-   Does this base overlap another?
-   Is this target within range?
-   Does terrain intersect line of sight?

Different games can use different coordinate systems.

### Continuous 2D

Appropriate for games such as Armada and Warhammer 40K:

``` text
Board width/height

Unit:
x
y
rotation
base geometry
```

### Square grid

Could use:

``` text
(x, y)
```

### Hex grid

Could use axial coordinates:

``` text
(q, r)
```

Conceptually:

``` text
Board
├── Dimensions
├── Coordinate system
├── Terrain
├── Zones
├── Objectives
└── Placed objects
```

## Board responsibilities vs rules

The board determines spatial facts, for example:

> This movement path intersects woods.

It should not itself decide:

> Therefore this unit loses 2 movement.

The latter belongs to game rules and characteristics.

Similarly:

``` text
Board:
"This ship is 300 mm from that ship."

Rules:
"Weapon X can attack targets at <= 400 mm."
```

This keeps spatial representation independent of individual games.

## Geometry

**JTS Topology Suite** was discussed as a strong Java library for
spatial geometry.

It provides concepts such as:

``` text
Point
LineString
Polygon
Geometry
```

Potential uses include unit base geometry, terrain polygons, movement
paths, firing arcs, range, collision detection, line of sight and
objective zones.

Conceptually:

``` java
movementPath.intersects(asteroidField);
shipGeometry.intersects(otherShipGeometry);
```

JTS is expected to be particularly useful for continuous tabletop games
such as Armada and 40K.

## Graphs and pathfinding

**JGraphT** was discussed as potentially useful for games using discrete
boards.

It could support shortest paths, reachable nodes, weighted routes,
terrain costs and routes avoiding threats.

It is potentially more relevant to grid/hex games than free-form Armada
movement.

## Movement decisions

There is a deliberate separation between:

> Where a piece **can** move

and:

> Where the AI **wants** it to move.

The rules engine determines legal movement. The AI selects among legal
options.

Conceptually:

``` text
Current Game State
        ↓
Determine units allowed to act
        ↓
Generate legal actions/moves
        ↓
AI evaluates candidate actions
        ↓
Simulate resulting state
        ↓
Score states
        ↓
Choose action
```

The AI should not invent an arbitrary move and hope it is legal.

Instead:

``` text
Unit
  ↓
LegalMoveGenerator
  ↓
List<Move>
  ↓
AI
  ↓
Selected Move
```

The implementation of `LegalMoveGenerator` can differ by game. Armada
might generate valid positions/headings according to manoeuvre rules.
40K could generate positions within a legal movement distance while
respecting terrain and collisions. BattleTech could generate reachable
hexes based on movement points.

## AI architecture

The initial AI should not rely on an LLM for deciding moves.

The basic approach is conventional game AI:

``` text
Generate legal actions
        ↓
Predict resulting states
        ↓
Evaluate each state
        ↓
Consider opponent responses
        ↓
Choose preferred outcome
```

Potential evaluation factors include objective value, attack
opportunity, expected damage, incoming damage risk, terrain, cover,
position, firing arcs, unit survival and victory points.

An abstract score might resemble:

``` text
score =
    objective_value
  + attack_opportunity
  + positional_advantage
  - incoming_damage_risk
  - terrain_penalty
```

Harder AI could look ahead into likely opponent responses using
techniques such as minimax or Monte Carlo Tree Search.

## AI difficulty

Difficulty does not need to mean simply making easy AI deliberately
stupid.

Potential parameters include:

``` text
search depth
planning horizon
risk tolerance
aggression
objective focus
target efficiency
positioning skill
mistake probability
```

Individual AI-controlled units could eventually have different tactical
competence.

## AI vs AI demo mode

A future demo mode could allow METIS to play both sides:

``` text
AI Player 1
      ↓
Game Engine
      ↑
AI Player 2
```

The projector could render the entire battle digitally without physical
miniatures.

An LLM could eventually be useful for explaining AI decisions even if it
is not responsible for choosing moves.

## Suggested technology direction

### Backend / engine

-   Java 21
-   Core engine as plain Java
-   Spring Boot around the engine for application/API functionality

### Database

-   PostgreSQL
-   JSONB for flexible characteristic data
-   Flyway for schema migrations

### JSON

-   Jackson

### Geometry

-   JTS Topology Suite

### Graphs/pathfinding

-   JGraphT where appropriate

### Maths

-   Apache Commons Math if needed

### Validation

-   Hibernate Validator

### Testing

-   JUnit 5
-   AssertJ
-   jqwik for property-based testing

### Frontend

-   React
-   TypeScript

### Infrastructure

-   Docker
-   Docker Compose

## Core engine independence

A key preference is keeping the core engine independent of
infrastructure.

The engine should ideally have no knowledge of Spring, React, PostgreSQL
or Docker. Those technologies should surround the engine rather than
define it.

Conceptually it should eventually be possible to run something like:

``` java
Game game = loader.load("armada");
GameState state = game.createMatch();
Action action = ai.chooseAction(state);
```

inside a plain Java test without running a web server or database.

## Timefold

Timefold was discussed because of prior experience with it. It is not
currently considered the natural primary opponent-AI engine.

Timefold is well suited to large optimisation/assignment problems. A
tabletop opponent instead repeatedly acts, observes an opponent
response, handles randomness and evaluates changing game states.

Timefold could potentially be useful later for areas such as fleet
composition, army building or deployment optimisation.

## Rules engine

A heavyweight rules engine such as Drools was mentioned but is not
currently required.

A simpler abstraction could eventually resemble:

``` java
interface Rule {
    RuleResult evaluate(GameState state, Action action);
}
```

Potential rule implementations could include movement, attack range,
line of sight, terrain, deployment and victory rules.

The database can supply parameters while Java supplies executable
behaviour.

## Physical tabletop future

A possible future architecture is:

``` text
Overhead Camera
      ↓
Computer Vision
      ↓
Piece Identification
      ↓
Coordinate Conversion
      ↓
METIS Game State
      ↓
Rules + AI
      ↓
Projector
      ↓
Physical Table
```

A human moves their own pieces. The camera recognises the movement.
METIS validates and records it. The AI determines its move. The
projector indicates the destination/orientation of the AI piece, and the
human physically moves it.

This avoids requiring robotic miniature movement.

## Project name

The project is named:

# METIS

**Modular Engine for Tabletop Intelligence & Strategy**

The name also references the Greek concept/personification **Metis**,
associated with wisdom, cunning, planning and strategic intelligence.

Potential module naming discussed included:

``` text
metis
├── metis-engine
├── metis-ai
├── metis-data
├── metis-api
├── metis-web
└── games
    ├── armada
    └── ...
```

This module structure was illustrative rather than a final architectural
decision.

## Current status / next design point

The work is currently in the **planning and database modelling stage**.

The current focus has been:

1.  Generic game/faction/unit hierarchy.
2.  Type/variant characteristic inheritance.
3.  PostgreSQL/JSONB for flexible characteristics.
4.  Terrain types and default terrain behaviour.
5.  Beginning to consider game rules and match definitions.
6.  Exploring how the eventual board and AI movement engine might work.

The schema, match model, rules model, board model and runtime game-state
model are **not yet finalised**.

The current dataset inventory and modelling backlog is maintained in
`DATASET_MODELLING.md`. It distinguishes the seeded Armada catalogue from
datasets that still require design, including source provenance, unit
semantics, upgrades, fleet legality, rules, scenarios, spatial definitions,
runtime state and AI evaluation data.

The staged delivery plan from the current catalogue through a browser POC,
constrained MVP, broader Armada releases and eventual multi-game support is
maintained in `METIS_PROJECT_PLAN.md`.

When continuing development, preserve the distinction between agreed
design decisions and ideas that were merely discussed as possible future
directions.
