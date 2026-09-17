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
and view private games after an owner invitation. The administrator can see all
games and access the catalogue admin pages and engine logs.

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

The response contains the stable fixture keys and current session IDs for test
clients. The numeric game ID is the catalogue game ID, not a session ID.

## Design documentation

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
