# Rules acceptance runner (M0)

## Run from a clean checkout

Prerequisites: Java 21, Maven, Node/npm (CI pins Node **24.16.0** in `.nvmrc`; the
runner accepts Node 22+), and Docker configured for Linux containers. Initial setup
needs network access for Maven/npm dependencies, Docker images and Chromium.
On Linux, Playwright's automated OS-dependency installation needs root/sudo access.
The runner uses non-interactive `sudo -n env DEBIAN_FRONTEND=noninteractive
NEEDRESTART_MODE=l ... install-deps chromium`, then downloads Chromium separately.
Root execution uses the same environment without sudo. This preserves the settings
across sudo's environment boundary and writes separate dependency/browser logs.

From the repository root, in PowerShell or a Unix shell:

```text
mvn -B -Prules-full clean verify
```

This command:

1. Builds all modules, runs Java regressions and JSON scenario fixtures, checks core
   architecture and writes JaCoCo reports.
2. Verifies each required Java module actually ran tests with zero failures/skips.
3. Tests runner failure propagation, empty-suite rejection, timeouts and report output.
4. Runs `npm ci` against `webapp/package-lock.json`, installing pinned Playwright and
   its Chromium browser (no global Playwright or developer Edge installation required).
5. Allocates a unique network, fresh tmpfs PostgreSQL 16.15 database, packaged Java 21
   application and ephemeral localhost port. It does not use `docker/.env`, the local
   compose network, existing sessions or the developer's database.
6. Bootstraps SQL 001–031, validates all packaged Flyway migrations, restarts the app
   and verifies migration history/checksums are unchanged. Host ports are rediscovered
   after restart because Docker can reallocate ephemeral mappings.
7. Runs the SQL checks and existing four-user/browser regressions, then the interpreted
   API suite. That suite publishes fixtures through HTTP, verifies privacy/idempotency,
   injects transactional failures in the disposable database and kills/restarts the
   application at controlled recovery checkpoints.
   M4's mechanics suite adds post-roll/reroll recovery, exact outcome checks, private
    hand/entropy projection and SQL anti-reseed/rewind tests.
   M5 adds spatial preview/commit parity, privacy/restart checks and completion of all
   three contrasting games through authenticated projected HTTP offers.
   M6 adds mode provenance/capture compatibility, exact overrides, concurrent catalogue
   imports, injected import rollback and base/two-mode completion with restart/replay.
    M7 adds persistent CPU recovery and mandatory generic-browser authoring/play,
    exact numbers, role privacy, drafts/focus/pan preservation, retry and historical views.
    The sourced-points slice adds source-revision/immutability SQL checks, shared
    preflight/setup validation, priced human/CPU games, restart/replay and browser
    cost controls. Legacy browser checks also verify post-roll defense gating and
    roll preservation across reload and replay.
    The selected-roster suite adds faction/quantity selection, atomic creation
    failures, immutable initial selections, newer-revision isolation, multi-unit
    human/CPU games, browser draft preservation and stale-setup rejection.
    The personal preset suite adds owner-only save/read/load/rename/delete,
    optimistic edit races, transaction rollback, cross-revision remapping and
    validation, immutable saved snapshots, session independence and browser reuse.
    The Counter squadron suite adds four-profile/keyword provenance, mixed fleets,
    Counter/Swarm response recovery, exact weighted-die outcomes and a browser
     lost-acknowledgement retry during a Counter reroll.
     The ship-maneuver suite adds pinned ship costs/charts/calibration, rectangular
     final-pose and used-tool validation, human/CPU courses, process restart and
     exact retries, browser speed/joint/side controls, delayed-preview rejection,
     retained drafts and lost-acknowledgement recovery with historical poses.
8. Captures logs, reports and browser traces, removes its labeled containers/network,
   and verifies removal.
9. Starts a second disposable environment and intentionally fails an assertion after
   application startup. The outer cleanup-contract check succeeds only if the child
   exits nonzero for that exact assertion and all its resources are removed.

The regular app on port 8080 can remain running. Normal failures and interrupt signals
enter cleanup. A forced process/host termination can prevent any finally block from
running; resources are identifiable by their unique `metis.rules.run` Docker label.

Fast Java checks: `mvn -B test`. Runner contracts only:
`node --test tools/rules-tests/runner.test.cjs`.
M1/M2 also add generated expression/game properties, package coverage gates and a focused
mutation profile: `mvn -B -pl engine-core -Prules-mutation clean verify`.
Combine it with full acceptance using `mvn -B "-Prules-full,rules-mutation" clean verify`.
`rules-extended` is not implemented yet. The full profile includes durable recovery
and M4 RNG/roll plus M5 geometry/CPU checks; broader performance and compatibility qualification follows.

## Reports

- `*/target/surefire-reports/`: JUnit outcomes, including six properties with 378
  generated/exhaustive cases after M5; M0's initial baseline was 84 tests.
- `*/target/site/jacoco/index.html`: Java coverage baseline; new-language thresholds
  are enforced separately for language and runtime at 90% line / 85% branch coverage.
- `engine-core/target/pit-reports/`: focused mutation results when `rules-mutation`
    is enabled. Targets include compiler/runtime/mechanics/stream and M5 spatial/scenario
    code plus the M6 resolver and the maneuver planner/geometry. Survivors remain counted, not excluded; the minimum detection gate is 80%.
- `engine-core/target/rules-benchmark.json`: M7 versioned timing/environment and bounded
  history-growth report. Full runs record wall-clock timings; the dedicated CI
  performance job enforces their thresholds. See `RULES_CLIENT_M7.md`.
- `engine-api/target/rules-scenarios/`: reproducible scenario-failure JSON including
  original fixture, definition hash, exact random tape/consumption, logical time,
  failed step and observed state.
- `target/rules-suite/<unique-run>/summary.json`: stage results, test count, migrations,
  allocated URL and cleanup status.
- Same directory: `TEST-rules-full.xml`, SQL/npm/browser logs, application/PostgreSQL
  logs and `orders-browser.zip`. A browser failure also captures a screenshot.

The failure probe deliberately has `probe: true`, `status: failed` and
`cleanup: verified`; that is its expected child result. The Maven outer check must
still pass. Similarly, `intentional-oracle-probe` and `intentional-tape-probe` are
expected-negative harness self-tests, not failed Java tests.

CI in `.github/workflows/rules-tests.yml` runs fast checks on Linux/Windows and the
full suite plus a separate mutation job on Linux, uploading artifacts even when a job fails. Hosted CI execution
occurs when the workflow is run in the repository; local success is not a claim that
remote CI has already run.

## Add a baseline scenario

Put JSON in `engine-api/src/test/resources/rules-scenarios/` and register its filename
in `manifest.json`. Schema version 1 requires:

- `id`, `adapter`, `requirements`, `logicalTime`, `randomTape`.
- A `definition` and `initialState` using current engine DTOs.
- Nonempty `steps`, each with `action` and `expect`: `accepted`, `code`, cumulative
  `randomConsumed`, and nonempty `state` assertions keyed by JSON Pointer.
- Optional `expect.views`: owned `players`, projected `state` assertions and `absent`
  JSON pointers for values that must not be exposed.

Each manifest scenario runs twice and compares the final state. Rejections must
leave state and RNG untouched; extra or unused tape values fail. Literal state IDs
and recorded logical time make failure artifacts reproducible. The current engine
does not consume a clock; the fixed clock is fixture context for future adapters.

`legacy-authoritative` is the only current scenario adapter. These are regression
fixtures for today's engine, **not executable v1 language definitions or the M2
cross-game runtime acceptance fixtures**. M1's language examples and schema are under
`engine-core/src/main/resources/rules`; compiler/expression/conformance fixtures are
under `engine-core/src/test/resources/rules`. M2 game scripts live under
`engine-core/src/main/resources/rules/scenarios`, and runtime tests under the matching
`rules/runtime` Java test package. These run through the new interpreter without
silently changing the legacy fixture semantics.

## Isolation and discovery contracts

`manifest.json` in this directory declares required Java modules, SQL suites and
HTTP/browser completion checkpoints. Missing/empty suites, failed subprocesses,
timeouts, absent browser checkpoints and cleanup failures make the build nonzero.
The full profile packages the app before starting containers; run the Maven command
rather than pointing the runner at an arbitrary jar or reusing stale Java reports.

The orchestration uses Docker CLI with real PostgreSQL/application containers. M3
reuses this runner for transaction fault barriers, process kills and lost-response
checks; it does not need a second Testcontainers orchestration layer. Fault triggers
are installed only in the disposable database, never by a production migration.
