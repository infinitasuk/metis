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
and generic game objects. The runtime foundation is being built behind the
versioned API boundary.

Definition lifecycle operations are available through the Admin API under
`/api/v1/admin`: revisions can be created as drafts, validated, published and
retired. Published definitions are protected while full revision-scoped
catalogue editing is being connected to the Admin UI. Creating a draft now
deep-copies the relational catalogue graph, and draft-owned object updates are
available through the Admin API.

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
