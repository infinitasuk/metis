# M6 — Resolved modes and the first catalogue migration

M6 adds `rules-resolver-1`, publication-time mode composition, immutable resolved
captures, author-facing provenance and a bounded Armada catalogue import. The first
slice is **one generic TIE Fighter Squadron versus one generic X-wing Squadron**,
with standard, close-engagement and short-battle variants. This is a development
training format with an explicit supported scope, not full Armada certification.

## Author a mode

Publish a normal language-v1 base definition using the existing admin API. A mode
uses the same draft/edit/validate/publish routes, with this authoring envelope:

```json
{
  "modeVersion": 1,
  "id": "my-small-battle",
  "revision": "1",
  "extends": {
    "id": "my-base-game",
    "revision": "1",
    "fingerprint": "<copy the base's 64-character published fingerprint>"
  },
  "overrides": [
    {"op":"replace", "path":"/state/roundLimit/initial", "value":3},
    {"op":"replace", "path":"/flow/phases/battle/steps", "value":[
      {"id":"act", "actions":["move","attack"], "minimum":0, "maximum":1}
    ]}
  ]
}
```

The placeholder fingerprint must be replaced with the actual published value.
References are exact `(id, revision, fingerprint)` pins: there is no `latest` alias.
Each mode has one parent, which can itself be a published mode. Draft bases cannot
be used for publication. Self/transitive cycles, missing bases, identity mismatches,
stale fingerprints and incompatible final rules fail explicitly.

### Override semantics

- Overrides execute in array order. Later writes to the same field win and remain
  visible in the change history.
- `add` requires an absent object member; `replace` and `remove` require an existing
  member. All parent objects must already exist. `null` is an ordinary JSON value,
  not a deletion shorthand; the rules compiler decides whether it is valid.
- Paths are non-root JSON Pointers with `~0`/`~1` escaping. Array elements cannot be
  edited individually: replace the whole ordered array. Objects are also replaced
  whole when directly targeted. There is no implicit deep merge, list concatenation
  or array-by-name reconciliation.
- The resolver sets the final `id` and `revision` from the mode envelope. Overrides
  cannot alter those fields or `languageVersion`.
- Every resolved layer is compiled against installed language/module contracts.
  Removing referenced procedures or breaking types/phase graphs is rejected before
  play. Source numbers retain the compiler's integer/decimal distinction and precision.

Bounds: 16 documents per chain, 256 overrides per document, 1,000,000 characters per
source/expanded definition/provenance document, 4,000,000 source characters across
the chain, and 64 path/JSON nesting levels. Decimal precision/scale limits are checked
before expanding exponent notation. Resolution has no IO or implicit catalogue access;
its caller supplies the exact-reference lookup. There is currently no compiled-definition
cache; content/version changes cannot reuse a cached plan by catalogue identity.

## Publication, inspection and capture

V38 adds `resolved_json` and `resolution_json` alongside the authored `source_json`.
Publication atomically stores:

1. The authored base or mode document.
2. Fully resolved, compiled canonical rules and their existing compiler fingerprint.
3. Resolver version and ordered ancestry with canonical source hashes and resolved
   fingerprints.
4. Leaf/empty-container JSON-Pointer provenance and ordered add/replace/remove history.
5. Immutable dependency links to the pinned published ancestors.

`GET /api/v1/admin/rules/definitions/{id}/resolved` returns `valid`, exact resolved
`source`, `fingerprint` and `resolution`; invalid drafts return diagnostics. Published
inspection reads stored provenance. This is an admin authoring endpoint. Public
definition lists and gameplay projections do not return authored rules or provenance.

New sessions capture the **resolved definition and resolution metadata**. Play,
restore and replay use that capture, never a fresh lookup of the base or mode. Editing
a draft or publishing a newer base revision cannot modify a running session. SQL guards
reject updates to published rules, ancestry and session resolution captures.

Existing compiler3/runtime2, compiler4/runtime3 and compiler5/runtime4 definitions
retain their contracts and fingerprints. Mode composition is upstream of compilation,
so M6 does not invent a new runtime version. Existing pre-M6 records with no resolution
metadata remain readable and report `resolverVersion:"none"`; new captures report
`rules-resolver-1`. Unknown captured resolver/compiler/runtime contracts fail compatibility
checks. Numeric legacy sessions retain `legacy-v1`/`activation-v1` dispatch; UUID rules
sessions have no fallback into those engines.

## Import and play the catalogue slice

The import recipe and executable policy are packaged as:

- `engine-core/src/main/resources/rules/catalogue/armada-squadron-training.pack.json`
- `engine-core/src/main/resources/rules/catalogue/armada-squadron-training.json`

The importer reads a **published catalogue revision** through its profile projection,
selects the two named generic variants, validates the supported fields and embeds those
values in ordinary language-v1 JSON. Hull, speed, base radius, faction, name, catalogue
identity and anti-squadron dice come from the captured catalogue data. Missing, duplicate,
malformed, unsupported-colour or defense-token profiles are rejected without defaults.
No gameplay action reads the live catalogue or calls the legacy battle engine.

Authenticated admin request (use the actual published catalogue revision ID):

```text
POST /api/v1/admin/rules/catalogue/imports
```
```json
{"gameVersionId":4,"pack":"armada-squadron-training-v1"}
```

The response contains three catalogue entries with `id`, `gameId`, `sourceVersionId`,
`pack`, `mode`, `name`, `definitionId`, `sourceFingerprint` and supported `scope`:

| Mode | JSON policy |
| --- | --- |
| `standard` | Six-round cap, starting x coordinates 200 and 800 |
| `close-engagement` | Inherits standard; starting x coordinates 300 and 400 |
| `short-battle` | Inherits standard; three-round cap |

The import fingerprint covers the catalogue snapshot, importer version, recipe hash
and template hash. Revisions use that full digest. V39 registers immutable catalogue
entries and source snapshots. A catalogue-revision lock serializes concurrent imports;
repeated imports of the same content return the same entries. Base publication, both
mode publications and registrations share one transaction, including rollback on a
failure in the last registration. Recipe changes require an explicit new import.

Any authenticated player can list `GET /api/v1/rules/catalogue` and create a session:

```text
POST /api/v1/rules/catalogue/{entryId}/sessions
```
```json
{
  "visibility":"private",
  "seats":[{"playerId":"alice","userId":1},{"playerId":"bob","userId":2}],
  "viewers":[]
}
```

This captures the entry's exact published definition. Supplying a replacement
`definitionId` is rejected. Thereafter use the existing UUID session commands,
projected `/actions` offers, preview, history and lifecycle routes. M7 now supplies
the generic browser and persistent CPU-seat scheduler at `/rules`; see
`RULES_CLIENT_M7.md`. All providers use ordinary interpreted commands.

## Parity, corrections and format boundaries

**Preserved catalogue data:** generic TIE hull 3/speed 4/three blue anti-squadron dice;
generic X-wing hull 5/speed 3/four blue anti-squadron dice; projected circular base radius
25. Tests also change profile values independently to ensure the importer copies data
rather than branching on variant IDs.

**Explicit development geometry:** speed is scaled by 100, attack/engagement distance
is 100 measured edge-to-edge, board size is 1000×600, and squadrons use destination-only
flight collision policy. These authored units are not a claim of calibrated official
rulers. Points estimates, fleet budgets and ship manoeuvre templates are outside this
fixed two-unit format.

**Deliberate corrections to the simplified legacy path:** blue faces have four hit,
two critical and two accuracy tickets; only hits damage squadrons. There are no automatic
miss rerolls or forced hits. A squadron gets one move **or** attack per activation and
cannot move while engaged. Elimination is checked immediately; the authored round limit
produces a draw, not an inferred winner. Exact all-hit and all-non-hit tapes verify this.

**Inapplicable in this roster:** Swarm has no second friendly squadron, Escort has no
alternative friendly target, and Bomber has no ship target. This is why those catalogue
keywords do not require an implicit handler in this slice. Adding further units, ships,
aces or upgrades requires an expanded definition and acceptance scope.

## H01–H22 migration classification

Each M0 hardcoded dependency is classified for this slice. “Legacy-only” means retained
for numeric sessions, not called by the interpreter. Broader Armada migration remains
incremental and must expand this ledger explicitly.

| Inventory | Classification in migrated scope |
| --- | --- |
| H01 Setup/player constraints | Migrated: two-seat JSON requirement and fixed captured roster |
| H02 Unit/state/board fields | Migrated: typed entity attributes/resources and explicit spatial bindings; profile adapter is import-only |
| H03 Phase/class switching | Migrated: authored squadron/automatic-scoring phase graph and selectors |
| H04 Activation budgets/endings | Migrated: one action per entity; explicit limits and draw procedure |
| H05 Hardcoded action switch | Migrated: registered instructions and authored move/attack procedures |
| H06 Deployment/default speed | Migrated: captured speed data and JSON placement via `space.deploy` |
| H07 Movement/reset assumptions | Migrated movement/preview; movement reset and tentative editing are unsupported in this format |
| H08 Sampled geometry/clicks | Migrated circular geometry; ship clicks/templates are outside scope and legacy-only |
| H09 Range/arc/dice selection | Migrated edge range, LOS and catalogue anti-squadron pools; ship arcs are outside scope |
| H10 Hit/reroll/forced-hit shortcuts | Migrated and deliberately corrected: explicit blue symbols/result table, no forced damage |
| H11 Defense/shield shortcuts | Inapplicable: both selected profiles have zero tokens and no shields; general legacy shortcuts are not called |
| H12 Victory/refresh | Migrated: immediate elimination and authored draw; token refresh is inapplicable |
| H13 Orders/support | Inapplicable: standalone squadron phase has no command ships; legacy orders remain legacy-only |
| H14 Fixed response callback | Inapplicable to these two generic profiles; shared interpreter reactions remain covered by M3–M5 |
| H15 Private projections | Migrated: seat-scoped rules views/actions/history; server entropy stays private |
| H16 RNG construction | Migrated: committed random stream and transactional outcomes |
| H17 Definition/version dispatch | Migrated: resolved mode/provenance captures with explicit compatible contracts |
| H18 Command persistence | Migrated: UUID commands, row locks, expected versions and idempotency receipts |
| H19 CPU shortcuts | Migrated headless provider: projected offers and ordinary commands; persistent CPU seats are M7 |
| H20 Hardcoded battle browser | Legacy-only; this scope is exercised through API/headless clients until M7 |
| H21 Formation/budget/preset UI | Outside the fixed-roster slice; legacy-only, with full roster authoring deferred |
| H22 Decorative terrain/upgrades/self-play | Self-play migrated; terrain/objective/upgrade catalogue presence does not enable unsupported rules in this slice |

## Verification

Run `mvn -B "-Prules-full,rules-mutation" clean verify`.

The suite covers scalar/object/whole-array overrides, ordered ancestry/provenance,
missing bases, cycles/depth/size/number limits, invalid references, exact decimals,
independent equivalent-definition fingerprints, standard/small/cooperative team play,
and seeded catalogue play. The real API suite covers publication, new base revisions,
stored captures, old null-metadata captures, unsupported runtime rejection, inspection
privacy, concurrent/idempotent imports, injected last-registration rollback, both modes,
process restarts and replay. Fresh migrations, repeated startup and all M0–M5 regression
gates remain mandatory. Hosted CI execution is separate from local verification.

### Local delivery record — 2026-09-23

- `mvn -B "-Prules-full,rules-mutation" clean verify` passed: **323 Java tests**, no
  failures/errors/skips; six properties exercised **378** generated/exhaustive cases.
- Both language/runtime coverage gates passed. Expanded mutation testing detected
  **590 / 683 mutants (86%)**, including the resolver. SQL, Chromium, recovery,
  mode/catalogue and intentional-failure cleanup checks passed.
- Full report: `target/rules-suite/metis-rules-1790182228313-07e3d8b0/summary.json`.
- Local deployment at `http://localhost:8080` upgraded the existing database from
  V37 to **V39**. Catalogue revision 4 was imported through the authenticated admin
  API; repeated import returned the same entries. Entry/definition IDs **1, 2, 3** are
  standard, close-engagement and short-battle respectively.
- Session 139 remains paused at round 286, version/event count 8407, with checksum
  `350934b1539e9e029a09f4737f227853` unchanged across the upgrade and import.
