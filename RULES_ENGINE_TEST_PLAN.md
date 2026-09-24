# Rules engine — full automated test suite plan

Date: 2026-09-23. Status: **M0–M7 suites implemented with local verification; hosted release CI pending**, following
[RULES_ENGINE_DEVELOPMENT_PLAN.md](RULES_ENGINE_DEVELOPMENT_PLAN.md).

The repository now has the `rules-full` Maven profile, disposable Docker orchestration,
CI workflow, JUnit/ArchUnit/JaCoCo checks, deterministic JSON baseline scenarios, SQL
checks and mandatory HTTP/Chromium integration tests. See
[runner instructions](tools/rules-tests/README.md). The latest clean combined run passed
290 Java tests (including four properties exercising 242 generated/exhaustive cases),
all existing integration stages and the intentional failure/cleanup contract. Checks cover
both complete example definitions, negative fixtures, all registered core instructions,
all core expression operators, typed pure mechanical queries, complete headless games,
serialized/fresh-runtime resumption, replay, phase policies and rejection boundaries.
Language/runtime package coverage gates enforce 90% lines / 85% branches. Focused PIT
testing detected 338 of 372 mutations after extending it to mechanical handlers and
the bounded random stream as well as runtime control flow.
The full suite now verifies real database/API publication and projection, simultaneous
writers, duplicate commands, rollback between writes, a lost committed HTTP response,
and actual process kills at a nested decision and inside an uncommitted transaction.
M4 also verifies exact weighted outcomes, roll privacy, no preview/retry consumption,
actual restarts after rolls, a kill during uncommitted reroll/payment, and SQL guards
against reseeding or cursor rewind. Hosted CI has not run from this working session;
broader performance/compatibility qualification remains later work.
Existing tests remain regressions;
new tests must verify the interpreter's contract rather than preserve known incorrect
legacy behavior. No placeholder or skipped tests count toward a milestone gate.

## Suite structure and implementation tasks

| Task | Layer and tool | Required coverage | First delivery |
| --- | --- | --- | --- |
| T01 | Build/orchestration: Maven Surefire/Failsafe, pinned Node/Playwright, CI | One reproducible entry point, required-suite discovery, nonzero failure propagation, reports and cleanup | M0 |
| T02 | Shared scenario harness: JUnit 5, versioned JSON fixtures | Definitions, state/setup, actions/decisions, prescribed random inputs, expected results, audience projections and traces | M0; extended every milestone |
| T03 | Language/compiler unit and schema tests | Valid/invalid syntax, types, references, versions, missing values, bounded recursion, normalization and stable diagnostics | M1 |
| T04 | Interpreter scenario and state-machine tests | Flow, continuation frames, budgets, choices, nested windows, deterministic ordering, rejection and terminal states | M2–M3 |
| T05 | Property/model-based tests: jqwik and a small independent reference model | Generated definitions/inputs, state invariants, bounded action sequences, shrinking and replayable seeds | M1–M5 |
| T06 | Module conformance suites | Every operation's declared input/output contract; exact dice/resource/card/geometry outcomes, limits and timing | M2–M5 |
| T07 | Real persistence/API integration: Docker/PostgreSQL and application process | Publication, snapshots, migrations, transactions, races, idempotency, fault recovery, HTTP contracts and ownership | M0 harness; M3 delivered; M6 upgrade matrix follows |
| T08 | Privacy and audience matrix | State, legal actions, decisions, events, traces, errors, session DTOs, replay and server-rendered content for four users | M3 onward |
| T09 | Replay, modes and compatibility | Event/state equivalence, version dispatch, immutable captures, overrides, upgraded and unsupported sessions | M2 baseline; M6 complete |
| T10 | End-to-end browser/CPU: Playwright and seeded headless self-play | Generic decisions for three game styles, human/CPU parity, reconnect, drafts, zoom/selection and action completion | M5 headless; M7 browser |
| T11 | Architectural rules, coverage and mutation: ArchUnit, JaCoCo, PIT | Core isolation, shared authority, explicit operation registry, critical rule-branch test sensitivity | M0 harness; M1–M7 gates |
| T12 | Performance/resource regression harness | Compile/action/preview cost, state/event growth, execution limits, bounded loops and reaction-depth limits | Baseline M2; qualify M7 |

Use the current Maven modules rather than building a duplicate test application.
Keep fast framework-free tests in `engine-core`; persistence/HTTP tests exercise
the real `engine-api`/`webapp` boundary and `database` migrations/routines. Keep
browser tests under the web test tree with a lockfile and pinned browser version.
Share versioned fixtures through test resources with an explicit common loader.

## Scenario fixtures and independent expected results

Each named scenario records:

- Requirement IDs (R/T/M), definition and mode revisions, and required module versions.
- Initial setup or state, players/owned seats, supplied clock/IDs and random outcomes.
- Submitted actions and decisions, expected accepted/rejected codes, state/event
  changes, pending decisions and remaining legal actions after each step.
- Expected owner/opponent/spectator/admin observations where privacy is relevant.
- Terminal outcome or the deliberately tested nonterminal/recovery checkpoint.

Use human-reviewed expected results derived from the language specification and
worked rules examples. Do not calculate expected damage, movement or token costs
with the same production helper being tested. Golden files require reviewed diffs;
automatic snapshot regeneration is not proof of correctness.

Every registered operation must have success, invalid-input, boundary and state-change
assertions; stateful operations additionally need retry/recovery tests, and private
operations need projection tests. A machine-readable feature-to-fixture manifest
must make an untested registered capability fail the conformance gate.

## Required scenario matrix

### Definition and mode validation

- Empty/duplicate identifiers, dangling references, wrong types, unknown operations,
  unsupported versions, invalid numeric values and missing required policies.
- Expression scope/type errors, missing versus null values, overflow/division/rounding
  semantics, collection ordering and execution limits.
- Unbounded recursive procedures and inheritance cycles rejected; explicit round
  loops and bounded iteration remain legal.
- Valid base/mode overrides; collection replace/merge behavior; conflicts and
  dependencies removed by an override; final resolved definition validation.
- Equivalent formatting/map-key order has the same canonical fingerprint; changes
  to semantic ordering or rule behavior change it. Cache invalidation follows versions.

### Execution, rounds and timing

- Alternating players, entities and groups; whole-player phases; simultaneous secret
  plans; automatic phases; empty/exhausted participants; passes and required actions.
- Optional/mandatory steps, exact budget boundaries, phase/round endings, victory
  during an action and no actions after termination.
- Branches, nested scopes, loops, continuation serialization and unknown instruction
  handling; malicious/oversized inputs cannot escape declared execution bounds.
- Before/after-roll windows, priority ordering, nested reactions, decline/pass,
  no eligible responders, destroyed/ineligible responders and stale decisions.
- Legal queries/previews neither change state nor consume randomness. Under the same
  state/version, valid offered choices are accepted with their required parameters;
  an empty legal set means waiting, termination or a defined pass, not a silent deadlock.

### Dice, resources, effects and space

- Exact face/weight boundaries and supplied RNG values, numeric/symbolic pools,
  zero pools, additions/removals, cancellation, selectable rerolls and result tables.
- Per-type/total capacities, costs paid once, order/token combinations, exhaustion,
  refresh, discard, overflow policy, durations, stacking and cleanup.
- Private draw/discard/shuffle state, exhausted decks and definition-selected policies.
- Movement at/exceeding bounds, occupied cells, base/zone boundaries, line of sight,
  path legality, heading constraints, distance units, reset/commit and preview parity.
- Damage/restoration caps, eliminated entities, objectives, ties and exact round limits.
- Parameterize the same module with different JSON to produce different expected
  results. Include a no-dice/no-token game to detect accidental default mechanics.

### Transactions, interruption and concurrent actions

- Two requests using the same expected version: exactly one winning transition and
  one defined conflict, with no partial event/resource/continuation write.
- Identical idempotency key/payload returns the prior accepted result without repeated
  effects; the same key with different payload is rejected. Keys are actor/session scoped.
- Duplicate/out-of-order/stale response and replay submissions; reconnect/retry after
  the response was committed but the client did not receive it.
- Inject failures before persistence, between state/event/continuation writes, and
  immediately after commit. Verify transaction rollback or recovery as appropriate.
- Stop and restart the application at every persisted decision checkpoint, including
  after dice have been rolled. Compare with the uninterrupted final state/history.
- Rejected/rolled-back work cannot advance the committed random stream; resumed
  accepted work cannot manufacture new random outcomes for an already committed roll.

Use barriers/latches and controlled fault points for concurrency tests rather than
hoping a timed race occurs. Do not use arbitrary sleeps to establish correctness.

### Privacy, authorization and observable contracts

- Independent owner, opposing player, spectator and administrator identities; human
  attempts to operate CPU seats and actor/target/choice spoofing.
- Private values checked across every outbound surface, including action responses,
  legal-action parameters, errors, logs/traces, replay, DTOs and HTML.
- Paired states differing only in an opponent's secret have the same allowed public
  observations where the rules declare that secret unobservable; public timing and
  counts follow explicit policy. This tests derived leaks as well as field filtering.
- Session identity changes and reconnect cannot retain another player's private view.
- API schema and structured failure-code checks use the real HTTP boundary, not only
  mocked controllers. A legitimate 403 must be understandable by the generic client.

### Cross-game, migration and client acceptance

- Same engine binary runs the resource-race/grid fixtures at M2 and all three complete
  acceptance games at M5; game/phase/action renaming does not change rule semantics.
- Scripted human decisions and a CPU given the same choices/outcomes obey the same
  validation. CPU strategy is constructed from the seat projection only.
- Fixed seed sets exercise setup-to-terminal self-play with action-count bounds. For
  fixtures designed to finish, exceeding a bound is a failed scenario, not a draw.
- Browser tests cover choice rendering, target selection, rerolls, tokens, response
  ownership, pending decision reload and preserved drafts/zoom/selection/drag behavior.
- Replay reconstructs the committed state at every checkpoint without new RNG or
  applying current rules to old events. Projected replay must retain historical secrecy.
- Empty database bootstrap, supported prior-schema upgrade and repeated startup;
  captured definitions immutable under publication, mode edits and migration.
- Old/new interpreter/module contracts either remain supported or return an explicit
  compatibility result. Separate restart never rewrites the source session/history.

## Automation and reproducibility

T01 now provides the fast and full commands. The extended profile remains planned;
the full profile executes all delivered acceptance layers and grows with each milestone:

```text
mvn -B test                         # current Java regressions and scenario harness
mvn -B -Prules-full clean verify    # implemented: isolated DB/app, SQL, HTTP, browser, cleanup probe
mvn -B -pl engine-core -Prules-mutation clean verify  # implemented: focused compiler/evaluator mutation gate
mvn -B "-Prules-full,rules-mutation" clean verify     # both delivered acceptance and mutation gates
mvn -B -Prules-extended verify      # PLANNED: extended seeds/fuzz/mutation/performance
```

The full profile must provision its disposable database, bootstrap/migrate it, start
the application on allocated ports, create fixtures, authenticate test identities,
run HTTP and browser tests, and tear everything down even on failure. Document
Java 21, Docker, Node and browser prerequisites; use pinned dependencies and automate
browser installation. Missing prerequisites or zero discovered required tests fail
the command. It never targets the developer's existing `metis` database.

Use fixed clocks/IDs and explicit or seeded random streams. For generated cases,
retain the original seed and minimized failing input. Pure rules tests use exact
outcomes rather than flaky sample-frequency assertions. Browser waits use observable
state/conditions with bounded timeouts. Infrastructure retries must not turn a failed
game assertion into a passing test.

Failure artifacts include fixture and definition hashes, language/module versions,
seed/random tape, accepted action sequence, checkpoint/continuation, expected/actual
state diff, projected rule trace and browser trace/screenshot where applicable. Test
fixtures contain synthetic secrets, never copied user-session data.

## CI and milestone gates

- **Every change:** fast Java/schema/architecture tests and coverage reports.
- **Every pull request:** full clean-environment suite on Linux, including real
  PostgreSQL/API tests and the browser scenarios delivered so far; Windows fast
  tests and runner portability checks. Newly completed milestones cannot be omitted.
- **Nightly:** larger property/fuzz and self-play seed sets, focused mutation tests,
  upgrade/recovery matrix and performance/resource checks.
- **Release candidate:** all full and extended gates run against that exact revision;
  an earlier nightly run on a different revision is insufficient. Verify the documented
  full command on Windows as well as the CI environment.

Planned quality thresholds for new compiler/interpreter packages: at least **90%
line and 85% branch coverage**; at least **80% mutation score** on selected critical
authorization, budget, resource, continuation and random-outcome logic. Capture the
initial measurements in M1 and review exclusions explicitly; do not exclude failing
features or lower a gate merely to make the build green. Coverage does not replace
the feature-to-scenario manifest or independent expected-result checks.

Architectural tests enforce core independence from Spring/JDBC/web code and declared
module boundaries. JSON-only variation tests and the R01 inventory provide the stronger
evidence against hidden game-specific behavior; a string search alone is insufficient.

M7 supplies `release-benchmark-1`, with recorded environment/fixture fingerprint and
versioned compile/offers/preview/submit/restore/replay thresholds plus history-growth
bounds. The full suite records `engine-core/target/rules-benchmark.json`; a dedicated
Ubuntu 24.04 / Temurin 21 CI job enforces wall-clock budgets. Limit/growth checks remain
deterministic rather than unit-test deadlines. See `RULES_CLIENT_M7.md` for fixture sizes.

**A milestone passes only when its scenarios, integrated regressions and mandatory
CI jobs pass.** Any accepted unsupported-v1 behavior is documented before release;
disabled/quarantined tests for a claimed supported capability block that claim.
