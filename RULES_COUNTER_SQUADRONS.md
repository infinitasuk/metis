# Mixed squadron catalogue and Counter attacks

Implemented 2026-10-09. Import recipe: `armada-squadron-counter-v1`.

## Supported roster

The new pack builds on the player-selected squadron format. It retains two seats,
a 26-point training allowance per seat, a maximum of three units per roster,
owner-specific deployment and alternating squadron activations.

| Squadron | Faction | Points | Hull | Source speed | Anti-squadron dice | Keywords |
| --- | --- | ---: | ---: | ---: | --- | --- |
| TIE Fighter | Galactic Empire | 8 | 3 | 4 | 3 blue | Swarm |
| X-Wing | Rebel Alliance | 13 | 5 | 3 | 4 blue | Bomber, Escort |
| A-Wing | Rebel Alliance | 11 | 4 | 5 | 3 blue | Counter 2 |
| TIE Interceptor | Galactic Empire | 11 | 3 | 5 | 4 blue | Counter 2, Swarm |

Examples: a TIE Fighter plus TIE Interceptor costs **19**; an X-Wing plus A-Wing
costs **24**. Standard, close-engagement and short-battle entries share the same
profile and combat contracts. Saved presets from the earlier two-profile format
can be loaded through the existing source-identity remapping and validation API.

## Source capture

V43 adds the immutable `bsdata-armada-counter-47bfca1` cost set. The original
two-profile source set retains its original contents. The new set copies those
two records and adds:

| File / revision | Squadron | Entry ID | Points |
| --- | --- | --- | ---: |
| Rebel Fleet.cat / 55 | A-Wing Squadron | 8557-fcfa-10e8-d19b | 11 |
| Imperial Navy.cat / 56 | TIE Interceptor Squadron | af57-88c9-9924-c93f | 11 |

Source repository: [BSData/star-wars-armada](https://github.com/BSData/star-wars-armada),
commit `47bfca1bddea459678568e5ee820597a433d9b41`, CC BY-SA 4.0. File SHA-256s:

- Rebel Fleet.cat: `d663dc3c24b4e5d53d0884475cc608879ed16183491dc04b6ad32f094bc5865c`
- Imperial Navy.cat: `803dde91e1db1b6be8bed6af70267cbe93e64f9c25cc53ca13da8dac926aae8c`

`tools/verify-armada-points.ps1` now verifies all four profiles, reporting direct
entry costs, hashes and raw squadron characteristics from the pinned commit.
Normal application imports use packaged data and require no source-server access.

`fn_rules_catalogue_keyword_source` augments the sourced profile projection with
keywords, keyword provenance and the squadron-ability field. Both characteristic
records must match the cost source's catalogue file/revision and profile name.
The importer rejects missing/duplicate/malformed keyword data, unknown keywords,
or nonempty named abilities. Recipe-owned `keywordDefaults` and `keywordBindings`
translate the captured keywords into ordinary typed rules fields; gameplay does
not branch on Java unit identities. Bomber is explicitly inapplicable because
this format has no ship targets.

The combat reference is the official [Rules Reference Guide 1.6.0](https://cdn.svc.asmodee.net/production-amgcom/uploads/2025/01/SWM_Rules_Reference_Guide_1.6.0_web.pdf),
Squadron Keywords, pages 19–20. Verified document SHA-256:
`c48ee9e97676aec77e998b58d36fe88e3024740a7206060352f64531c472e7fb`.
Its URL, version and digest are retained in the catalogue import snapshot's
`rulesSource` metadata.

## Attack and response contract

All effects and timing are authored in
`engine-core/src/main/resources/rules/catalogue/armada-squadron-counter.pack.json`.
The recipe composes the earlier roster pack through the existing mode resolver;
no interpreter or language-version change is required.

1. A normal attack checks ownership, target health, range, line of sight and Escort.
2. `strike` rolls the declared normal pool. An eligible attacker may use Swarm to
   reroll one chosen die while another friendly squadron engages the target.
3. Damage is applied and `game.attackResolved` records attacker, target, roll ID,
   applied damage and `counter:false`. A destroyed unit is marked and retained as
   a record, so its response can still resolve.
4. A Counter-capable defender is offered `counter` or `$pass`. Its owner is the
   designated responder even when that defender has been destroyed.
5. Counter uses the source-derived Counter pool (two blue dice here), the original
   attacker as its fixed target, and the defender's owner for any nested Swarm
   choice. It does not consume the defender's activation or action allowance.
6. Counter invokes `strike` directly and never opens another Counter window.
   The resolved event records `counter:true`.
7. Elimination is evaluated after the entire exchange, including all responses.
   A sole surviving side wins; mutual elimination is an authored training draw.

Escort prevents normal attacks against a non-Escort target when the attacker is
engaged with a living enemy Escort squadron. Counter is exempt from that targeting
restriction. Destroyed or out-of-engagement Escorts do not protect other units.

Existing response authorization, stale-decision checks, persisted continuations,
server-only random streams, idempotent command receipts and replay apply to Counter
and its nested Swarm choices. A reconnect resumes the recorded roll and pending
decision, rather than producing a replacement attack.

## Scope and compatibility

The pack uses development-unit geometry: catalogue speed multiplied by 100,
edge-to-edge engagement/attack range 100, and the existing circular footprints.
It is a squadron-training format; calibrated rulers, ships, aces, upgrades and
standard fleet-format caps remain subsequent work. Bomber's ship damage effect
is not applicable to any offered target.

Older catalogue entries and captured sessions keep their prior definitions.
V43 uses a new source set and keyword projection, and importing the new pack
creates new immutable definitions and catalogue entries. A preset load validates
the target revision while preserving its saved source snapshot.

## Use

As an authenticated administrator, with the actual published catalogue revision:

```text
POST /api/v1/admin/rules/catalogue/imports
{"gameVersionId":4,"pack":"armada-squadron-counter-v1"}
```

In `/rules`, choose **Mixed squadron training with Counter**, select each seat's
faction and squadron types, validate/save a fleet or load an existing preset,
and create the match. The generic decision controls offer Counter, pass and
Swarm die-selection choices to the appropriate player.

## Verification

Local `mvn -B -Prules-full verify` passed on 2026-10-09 with **352 Java tests**
(303 core, 44 API, 5 web), coverage gates, benchmark recording, all required
SQL/HTTP/Chromium/CPU suites and both cleanup checks. Report:
`target/rules-suite/metis-rules-1791550780965-5901d5a1/summary.json`.

- `CounterSquadronTest`: exact four-profile data, source-driven keyword binding,
  unsupported data rejection, lethal-defender pass/Counter, mutual elimination,
  Counter victory, no chains, nested Swarm and no defender activation cost,
  plus Escort and its Counter exception.
- `counter_squadrons.sql`: actual sourced costs/stats/keyword provenance, immutable
  source-set boundaries and preservation of older projections.
- `counter-squadrons.cjs`: concurrent/idempotent import, mixed human/CPU games,
  old and new presets, publication of a controlled engagement fixture, unauthorized
  responses, rollback, process restarts at Counter and nested Swarm, independent
  HMAC/weighted-face checks, lost-response browser retry and recorded replay.

The local Docker app was rebuilt and upgraded from V42 to V43. Catalogue entries
10–12 are available at `http://localhost:8080/rules`. An authenticated Chromium
smoke check verified all four profiles and successful validation of the 19-point
Imperial and 24-point Rebel mixed fleets on the deployed app.

Hosted CI release qualification is tracked by the GitHub Actions workflow in
`https://github.com/infinitasuk/metis`.
