# METIS Web

Spring Boot web application for simulating games and administering the static
METIS catalogue.

Static catalogue tables are stored in the PostgreSQL `game_engine` schema.
The `game_instance` schema is reserved for user game instances and runtime
game state.

## Run with Docker

Start PostgreSQL and the web application:

```powershell
docker compose -f docker/compose.yaml up -d --build
```

Open `http://localhost:8080` and choose either **Game Simulator** or **Admin**.

Database upgrades from version 32 run automatically through Flyway on application
startup, including existing database volumes. Initial catalogue bootstrap remains
in `docker/init/sql`. See [`database/README.md`](database/README.md) for migration
history, configurable profile bindings, and database regression checks.

The Game Simulator currently provides game and match setup surfaces. The Admin
area provides the static engine catalogue plus edit forms for game definitions
and generic game objects. The first authoritative engine slice is available
through the versioned API boundary: start a session, inspect its state, and
submit deployment, phase and bounded movement actions.

Definition lifecycle operations are available through the Admin API under
`/api/v1/admin`: revisions can be created as drafts, validated, published and
retired. Published definitions are protected while full revision-scoped
catalogue editing is being connected to the Admin UI. Creating a draft now
deep-copies the relational catalogue graph, and draft-owned object updates are
available through the Admin API. Open a specific revision with
`/admin/games/{gameId}?versionId={definitionId}`.

## Run the application locally

Start only PostgreSQL:

```powershell
docker compose -f docker/compose.yaml up -d postgres
```

From the project root, start the application:

```powershell
mvn spring-boot:run
```

Open `http://localhost:8080`.

The default database settings match `docker/.env`. Override them with `DB_URL`,
`DB_USER`, `DB_PASSWORD`, or `PORT` when required.

## Development Login

The development login screen provides four regular users (`alice`, `bob`,
`carol`, and `dave`) plus `admin`. It is intentionally passwordless for local
testing. Regular users can create games, see public games and their own games,
and view private games after an owner invitation or seat assignment. The administrator can see all
games and access the catalogue admin pages and engine logs.

Human seats can be assigned using **Controlled by** during setup. Gameplay and
private information follow seat ownership; an administrator or spectator does not
automatically control a seat or see its private choices.

New battle captures also support definition-configured private unit order queues,
reveal/token decisions and reusable command effects, with player and CPU controls.
See [ORDERS_CONTRACT.md](ORDERS_CONTRACT.md) for configuration, verification and the
current mechanical limits, and [GAME_ENGINE_FIX_BACKLOG.md](GAME_ENGINE_FIX_BACKLOG.md)
for delivery status.

## Load repeatable test games

The test-fixture endpoint requires an administrator session and ensures ten retained
CPU-versus-CPU template battles named `METIS-TEST-GAME-01` through
`METIS-TEST-GAME-10`. Existing fixture sessions are never deleted or reset:

```powershell
$webSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:8080/login" -WebSession $webSession | Out-Null
Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:8080/login" -Method Post -WebSession $webSession -Body @{ userId = 5; next = "/" } | Out-Null
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/test-fixtures/games/6" -WebSession $webSession -ContentType "application/json" -Body '{}'
```

The same request can be repeated safely. It preserves existing sessions and only
creates missing templates or sessions:

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/test-fixtures/games/6" -WebSession $webSession -ContentType "application/json" -Body '{}'
```

After loading the templates, open **Game Simulator**, choose a game, and select a
template in the Match step. The wizard copies the template setup into a new
session and records which template started that run. The original template battle
and all of its AI history remain unchanged.

The Admin page also provides **Load CPU suite**, which retains twelve source
battles covering four faction pairings across capital-ship, squadron, and mixed
loadouts. Use **Monitor CPU suite** to watch status, round, run number, AI version,
and replay links for the suite.

Players can start a match from **Game Simulator**, build a fleet in the Fleets
step, save it under a personal name, and reuse it in later matches. Starting the
match deploys the selected units onto the battlefield automatically.

The response contains the stable fixture keys and current session IDs for test
clients. The numeric game ID is the catalogue game ID, not a session ID.

## Design documentation

### Automated rules-engine baseline

With Java 21, Maven, Node/npm and Docker Linux containers available, run from the
repository root:

```text
mvn -B -Prules-full clean verify
```

This builds/tests Java, provisions an isolated database/app, checks migrations and
SQL contracts, runs multiplayer and mandatory Chromium browser checks, and verifies
cleanup after success and an intentionally failed child run. It installs its pinned
browser dependencies automatically. See [runner instructions](tools/rules-tests/README.md)
for prerequisites, reports and fixture authoring. M0–M7 are implemented locally, including
spatial/scenario modules, pinned modes, a bounded catalogue migration and the generic
browser/persistent CPU client at **http://localhost:8080/rules**. Hosted CI release
qualification remains pending. Compile a rules-language example with:

```text
mvn -q -pl engine-core -Prules-compile compile exec:java
```

Run an actual headless game, restoring its checkpoint between commands:

```text
mvn -q -pl engine-core -Prules-play compile exec:java
```

See [RULES_LANGUAGE_V1.md](RULES_LANGUAGE_V1.md) for the schema, example definitions,
compiler diagnostics and pure expression/module contracts. To include focused mutation
checks with full acceptance: `mvn -B "-Prules-full,rules-mutation" clean verify`.
See [RULES_RUNTIME_M2.md](RULES_RUNTIME_M2.md) for both playable headless examples,
runtime commands, phase policies, privacy and replay.
The deployed `/api/v1/rules` APIs now support durable interpreted sessions, publication,
seat-bound commands, history, idempotency and nested reactions. See
[RULES_API_M3.md](RULES_API_M3.md) for routes and request examples. The existing battle
UI serves captured numeric legacy sessions; the generic interpreted client is at `/rules`.
For the new dice/card/token example, module contracts and random-outcome recovery,
see [RULES_MECHANICS_M4.md](RULES_MECHANICS_M4.md).

Run a complete game with the projection-bound baseline CPU:

```text
mvn -q -pl engine-core -Prules-cpu compile exec:java
```

See [RULES_SPATIAL_M5.md](RULES_SPATIAL_M5.md) for movement/geometry semantics,
authoritative preview, setup/scoring, finite action choices and all three CPU examples.
See [RULES_MODES_M6.md](RULES_MODES_M6.md) for mode authoring, resolved provenance,
catalogue import/session APIs and the supported Armada squadron-training scope.
See [RULES_CLIENT_M7.md](RULES_CLIENT_M7.md) for browser play/authoring, CPU seats,
private drafts, retries/replay, performance budgets and the release boundary.

- `RULES_ENGINE_DEVELOPMENT_PLAN.md` is the primary delivery plan: establish a
  working rules-driven engine through milestones M0–M7 before extending battle UI.
- `RULES_ENGINE_TEST_PLAN.md` specifies the full automated suite, CI, deterministic
  fixtures, recovery/privacy checks and milestone/release gates.
- `RULES_LANGUAGE_BACKLOG.md` breaks the interpreter and language work into R01–R21.
- `RULES_LANGUAGE_V1.md` documents the implemented M1 language/compiler and its examples.
- `RULES_RUNTIME_M2.md` documents the implemented headless interpreter and game demonstrations.
- `RULES_API_M3.md` documents durable interpreted APIs, reaction policies and recovery verification.
- `RULES_MECHANICS_M4.md` documents configurable mechanics, durable randomness and the dice-duel example.
- `RULES_SPATIAL_M5.md` documents geometry, scenarios, movement preview and complete CPU games.
- `RULES_MODES_M6.md` documents pinned modes, resolved captures and the first catalogue migration.
- `RULES_CLIENT_M7.md` documents the generic browser, persistent CPU seats and release qualification.
- `RULES_ENGINE_M0_BASELINE.md` maps H01–H22 hardcoded behaviors to their replacements
  and defines the supported-v1 scope and compatibility boundary.
- `GAME_ENGINE_FIX_BACKLOG.md` prioritizes corrections and the reusable-rule-module
  architecture for additional games.
- `ACTIVATION_AND_SNAPSHOT_CONTRACT.md` documents captured definitions, safe legacy
  restarts, configurable activation policies and the supported sequencing slice.
- `MULTIPLAYER_CONTRACT.md` documents seat ownership, private views/choices,
  response windows and the isolated multiplayer integration checks.
- `ARMADA_RULES_REVIEW.md` records the current rules/data audit and remaining gaps.

- `METIS_Project_Handover.md` contains the broader project design context.
- `DATASET_MODELLING.md` tracks seeded datasets and the remaining modelling
  backlog.
- `BATTLE_SIMULATOR_MODEL.md` defines the runtime entities and API/database
  routine contract for the battle simulator.
- `ARCHITECTURE_DESIGN.md` records the agreed WebApp, GamingEngine, API and
  database ownership boundaries.
- `IMPLEMENTATION_PLAN.md` tracks the concrete development phases and immediate
  work queue.
- `VERSIONING_DESIGN.md` defines software, API, database, event and
  game-definition versioning.
- `METIS_PROJECT_PLAN.md` defines the staged path from catalogue to web POC,
  MVP, production Armada application and multi-game platform.
