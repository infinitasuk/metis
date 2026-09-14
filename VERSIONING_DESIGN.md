# METIS Versioning Design

METIS tracks independent version dimensions because software, APIs, database
structure and game content change for different reasons.

## Version Dimensions

### Software component version

The Maven components currently share the product version:

```text
engine-core  0.1.0-SNAPSHOT
engine-api   0.1.0-SNAPSHOT
webapp       0.1.0-SNAPSHOT
```

They may be released independently later. A Git commit SHA should accompany a
deployed build for diagnostics.

### API version

External contracts are versioned independently of implementation releases:

```text
/api/v1/...
/api/v2/...
```

Changes that remain backward compatible can stay within the same API version.
Breaking changes require a new version and a compatibility period.

### Database schema version

Database migrations are append-only and independently numbered:

```text
008_create_battle_runtime.sql
009_runtime_resource_routines.sql
010_add_runtime_version_metadata.sql
011_definition_lifecycle.sql
012_scope_catalogue_to_definition.sql
013_revision_scoped_object_updates.sql
```

The database schema version is not the same as a software or game-definition
version.

### Game-definition version

Game content has its own identity, source revision and lifecycle. A catalogue
version can be drafted, validated, published and retired. Published versions
are immutable.

For example:

```text
Game:                 Star Wars: Armada
Definition:           Armada 1.5 reference catalogue
Source revision:      41
Lifecycle:            published
```

### Event schema version

Persisted event payloads include an event schema version so that old sessions
can be replayed after the engine evolves.

## Runtime Version Record

Every simulation records:

- The exact game-definition version used.
- The engine software version.
- The public API version.
- The event schema version.

This allows a completed match to be diagnosed and reproduced after later code,
database or catalogue releases.

## Compatibility Rules

- The WebApp and engine API must be tested as a compatibility pair.
- Database migrations should use expand-then-contract changes where services
  may be deployed separately.
- An engine must reject a definition version it cannot interpret.
- A client must not send a software or definition version to override the
  server's selected version.
- A simulation is pinned when it is created and never silently follows the
  current published catalogue.

## Current Implementation

- Maven modules share `0.1.0-SNAPSHOT`.
- Runtime endpoints use `/api/v1`.
- Runtime sessions record engine, API, event-schema and game-version metadata.
- Database scripts are numbered in `docker/init/sql`.
- `GET /api/v1/meta` exposes the active API and engine version values.
- `GET/POST/PATCH /api/v1/admin/...` manages definition metadata and lifecycle.
- Definitions can be validated, published and retired through stored routines.
- Draft creation deep-copies the relational catalogue graph and assigns the
  copied game-owned rows to the new revision.
- Object updates are accepted only when the object and definition are both
  draft-owned.
- Admin catalogue pages accept `versionId` and display the selected revision;
  omitted revision IDs resolve to the published revision.

The next versioning task is to route all remaining Admin content mutations
through revision-aware procedures. Base game metadata and global
characteristic types also need revision ownership before the lifecycle is
complete.
