# METIS Database

V40 adds immutable HUMAN/CPU interpreted seats, a captured operational command limit,
fair row-claimed CPU scheduling metadata and projected session/workspace queries.
Existing interpreted seats default to HUMAN; prior game checkpoints remain intact.
See `RULES_CLIENT_M7.md` for CPU authorization, transaction, pause and retry contracts.

M6 adds V38 resolved mode/provenance captures and V39 immutable interpreted catalogue
entries. Authoring source is retained separately from executable resolved JSON. The
catalogue import is an explicit, transactional admin API operation after migration;
it records source data/recipe fingerprints and is idempotent. Existing captures are
not backfilled or reinterpreted. See `RULES_MODES_M6.md` for contracts and scope.

`docker/init/sql/001..031` is the legacy bootstrap for a new PostgreSQL volume.
From version 32, upgrades live in `src/main/resources/db/migration` and are packaged
in the database JAR. Spring Boot runs Flyway before repositories and CPU scheduling
start, including when PostgreSQL already contains data.

The first managed startup baselines the existing `game_engine` schema at 31, then
applies V32. The baseline describes the legacy schema, not proof that every old
function was deployed: V32 replaces the whole unit projection, including command
and token fields that some existing volumes missed. Bootstrap tables through 031
must exist; Flyway is an upgrade path, not a replacement for initial catalogue import.

Flyway stores versions/checksums in `game_engine.flyway_schema_history`. Never edit
an applied migration; add a new version. Repeated startup validates checksums and
applies only pending migrations. Flyway clean is disabled. Upgrade errors stop app
startup rather than running against a partially upgraded projection.

## Definition-owned profile bindings

`game_version.profile_mapping` is revision-owned JSON mapping source characteristic
names onto the existing typed runtime profile. No game-name branching is involved.
For example, either of these health bindings uses the same reader:

```json
{"health":{"sources":["Wounds"],"required":true}}
```

```json
{"health":{"sources":["Hull segments","Integrity"],"required":true}}
```

Sources are tried in order; explicit zero values do not trigger a fallback. Missing
required health and malformed numeric data fail instead of becoming health 1.
Optional mechanics can be omitted. New games have no inherited weapon range,
command or token configuration. Drafts cloned from a published revision copy that
revision's mapping. Published/retired mappings cannot be edited.

Admin API:

- `GET /api/v1/admin/definitions/{id}/profile-mapping`
- `PATCH /api/v1/admin/definitions/{id}/profile-mapping` replaces the mapping object
  on a draft, verifies projection of its current units, and records an audit entry.

Migrated legacy aliases/ranges are definition data, not universal rules. The output
still adapts to legacy `UnitDefinition` field names such as `maximumHullPoints`;
generic resources, rule modules, and grid/continuous geometry are follow-up work in
`GAME_ENGINE_FIX_BACKLOG.md`. Profile correction does not rewrite unit resources or
events in existing battles.

## Projection regression checks

After starting the migrated app:

```powershell
docker cp database/src/test/sql/profile_mapping.sql metis-postgres:/tmp/metis-profile-mapping-test.sql
docker exec metis-postgres psql -U metis -d metis -v ON_ERROR_STOP=1 -f /tmp/metis-profile-mapping-test.sql
```

The checks cover alias precedence, missing/invalid values, two independently mapped
game definitions, zero/default handling, immutable published mappings, and cloning.
Fixture rows are rolled back.

## Captured definitions and sequencing (V33)

V33 adds immutable per-session definition captures and revision-owned turn
structures. See `ACTIVATION_AND_SNAPSHOT_CONTRACT.md` for the restart policy and
configuration contract. Snapshot regression checks:

```powershell
docker cp database/src/test/sql/session_snapshots.sql metis-postgres:/tmp/metis-session-snapshots-test.sql
docker exec metis-postgres psql -U metis -d metis -v ON_ERROR_STOP=1 -f /tmp/metis-session-snapshots-test.sql
```

## Seat ownership (V34)

V34 assigns existing human seats to their session creator, introduces setup-only
seat assignment and participant visibility, and adds response policies for new
captures of the existing token-backed battle definition. It does not rewrite
captured definitions. See `MULTIPLAYER_CONTRACT.md` for the projection contract.

```powershell
docker cp database/src/test/sql/seat_ownership.sql metis-postgres:/tmp/metis-seat-ownership-test.sql
docker exec metis-postgres psql -U metis -d metis -v ON_ERROR_STOP=1 -f /tmp/metis-seat-ownership-test.sql
```

`multiplayer_fixtures.sql` is only for the isolated integration environment; do not
use it as a production migration.

## Queued orders (V35)

V35 adds optional order configuration to matching catalogue turn structures for
new captures. Capacity bindings, order names/timings, token limits, effect costs,
yaw limits and coordinated-activation policy are data in `turn_structure.orders`.
Captured definitions remain immutable. See `ORDERS_CONTRACT.md` for the module
contract and synthetic multiplayer/browser checks.

## Interpreted rules sessions (V36)

V36 adds draft/published rules definitions and separate UUID-based interpreted sessions,
immutable captures, fixed seat/viewer membership, append-only transitions and actor/user-
scoped command receipts. The command-save procedure checks expected versions and
checkpoint/commit equivalence and writes all records transactionally. Existing numeric
battle sessions and captures are not migrated into this runtime.

See `RULES_API_M3.md`. `rules_faults.sql` is test-only infrastructure installed by the
isolated full-suite runner, not a production migration or application feature.

## Durable random-stream guards (V37)

V37 extends the atomic interpreted-command save routine to preserve random-stream
identity and prevent cursor rewind. Entropy, outcomes and mechanics remain in the same
checkpoint/commit transaction. Core-only sessions retain their previous JSON/version
contracts. See `RULES_MECHANICS_M4.md` for post-roll restart and rollback verification.
