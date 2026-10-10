# Sourced Armada points and shared roster validation

Implemented 2026-10-03 as the next bounded catalogue slice after M8.

## Supported format

Import `armada-squadron-points-v1` to create standard, close-engagement and
short-battle entries. This is a fixed two-seat training format:

| Seat | Faction | Unit | Points | Training allowance |
| --- | --- | --- | ---: | ---: |
| 0 | Galactic Empire | TIE Fighter Squadron | 8 | 13 |
| 1 | Rebel Alliance | X-Wing Squadron | 13 | 13 |

The 13-point allowance is an authored training policy. Each seat has one generic
squadron; standard Armada fleet legality, squadron-percentage caps, ships,
upgrades, free-form roster selection and multi-squadron keyword interactions are
still future slices. Both generic units have an empty unique key. The shared
validator therefore permits repeated generic profiles when a definition's count
and budget limits permit them, while rejecting repeated named unique keys.

The follow-on `armada-squadron-rosters-v1` pack now supports player-selected
factions and quantities with immutable session capture. See
[RULES_ROSTER_SELECTION.md](RULES_ROSTER_SELECTION.md). This original priced pack
retains its fixed single-squadron format.

The recipe composes the M8 template through `RulesResolver`, then imports profile
and cost data. Its deployment suggestions select only the acting seat's zone and
starting position. Human and CPU clients use the same interpreted commands.

## Source provenance

V41 adds an immutable `game_engine.catalogue_unit_cost` source set named
`bsdata-armada-47bfca1`. Values were read from direct model-entry `pts` costs in
the community-maintained [BSData Armada catalogue](https://github.com/BSData/star-wars-armada),
pinned to commit `47bfca1bddea459678568e5ee820597a433d9b41`:

| File | Revision | Entry ID | Raw points | File SHA-256 |
| --- | --- | --- | --- | --- |
| Imperial Navy.cat | 56 | baa4-494e-4b1e-f5f2 | 8.0 | 803dde91e1db1b6be8bed6af70267cbe93e64f9c25cc53ca13da8dac926aae8c |
| Rebel Fleet.cat | 55 | 55f0-0a64-b063-acc8 | 13.0 | d663dc3c24b4e5d53d0884475cc608879ed16183491dc04b6ad32f094bc5865c |

Each record retains repository, commit, file, revision, entry ID, raw value,
SHA-256, pinned URL and CC BY-SA 4.0 attribution. Reproduce the source extraction:

```powershell
powershell -NoProfile -File tools/verify-armada-points.ps1
```

This read-only tool uses the pinned commit and reports the extracted records;
normal builds and imports use the packaged migration and require no source-server
connection.

The verifier also covers the A-Wing and TIE Interceptor records introduced by
V43's separate source set; see [RULES_COUNTER_SQUADRONS.md](RULES_COUNTER_SQUADRONS.md).

`fn_rules_catalogue_priced_source` joins the source set to the published profile's
game, faction, classification, name and original characteristic provenance
(catalogue file, revision and profile). An absent or mismatched source produces
no cost. Import rejects missing, nonpositive, fractional, out-of-range or
unverifiable costs rather than substituting estimates.

Costs and full provenance enter the immutable catalogue snapshot and published
definition. The import fingerprint includes those values, the composed template
and the recipe. Sessions retain their captured definition across restart and
replay. V41 is additive: existing development estimates, older import projection
inputs, M7/M8 entries and captured sessions keep their prior contracts.

## Shared validation and API

`RosterValidator` implements definition-owned count, entity ownership, duplicate
instance, nonnegative cost, checked budget sum, faction and unique-key checks.
`scenario.roster` and the catalogue preflight adapter both call it. Catalogue
session creation validates the fixed default rosters and seat count; runtime
setup validates again through `scenario.roster`, including CPU seats.

Import as an authenticated administrator, using the actual published revision ID:

```text
POST /api/v1/admin/rules/catalogue/imports
{"gameVersionId":4,"pack":"armada-squadron-points-v1"}
```

The existing import transaction includes base/mode publication and registration.
Repeated or concurrent imports of identical data return the same entries.

Any authenticated user may inspect:

```text
GET /api/v1/rules/catalogue/{entryId}/roster
```

The response contains `available`, the definition `fingerprint`, captured `units`
(codes, names, factions, costs, unique keys, provenance JSON strings), and
zero-indexed `seats` with policies, budgets, count limits, factions and fixed unit
codes. Older packs return `available:false`.

Validate a draft list using unit codes from that response:

```text
POST /api/v1/rules/catalogue/{entryId}/roster/validate
{"seat":0,"units":["<captured catalogue unit code>"]}
```

The read-only response contains `valid`, `code`, `points`, `budget` and
`fingerprint`. Ordinary invalid rosters return HTTP 200 with `valid:false` and a
specific `ROSTER_*` code. Unknown units have an unknown (`null`) total. Malformed
inputs, extra fields or invalid seats return HTTP 400; unsupported packs return
409. Requests cannot supply costs, budgets, factions or replacement definitions.
Validation does not save or select a roster for a session; this pack's roster is
definition-owned. Session creation uses the existing catalogue session endpoint.

The `/rules` lobby shows costs, source references, allowances and a **Validate
rosters** action. Create/start failures still come from the authoritative API.

## Verification

Local qualification on 2026-10-03:

- `mvn -B -Prules-full verify`: passed **335 Java tests** (303 core, 27 API,
  5 web), coverage gates, benchmark recording, all SQL/HTTP/Chromium suites,
  process-restart/replay and CPU completion, plus success/failure cleanup probes.
- The mutation stage of `mvn -B "-Prules-full,rules-mutation" clean verify`
  passed **597/689** mutations (87%). Its subsequent acceptance run found the
  deployment-offer issue, corrected in the recipe before the passing full rerun.
- `sourced_points.sql`: exact costs/source identity, source immutability,
  raw-value consistency, revision mismatch rejection and stable old projections.
- `CatalogueRostersTest` / `RosterValidatorTest`: preflight/runtime parity,
  budget boundaries, source failures, repeated generic/unique identities,
  ownership, count, faction, integer overflow and legal deployment offers.
- `sourced-rosters.cjs`: authenticated read-only validation, malformed-input
  rejection, concurrent/idempotent import, snapshot restart, human/CPU full games,
  terminal replay and browser cost/validation/session-creation controls.

Hosted CI release qualification remains pending.
