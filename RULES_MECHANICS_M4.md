# M4 — Dice, cards, resources and timed effects

**Delivered and locally verified, 2026-09-22.** The full suite passed 290 Java tests,
242 property checks, the coverage gates, SQL/API/browser regression checks, and the
new post-roll/process-kill recovery tests. A final bounded-sampling verification passed
the core suite and detected 338/372 mutations (91%) across the expanded selected code.
The dice-duel CLI completed successfully. V37 and the application are deployed locally;
session 139 remains paused with its prior checkpoint checksum. Hosted CI is configured
but has not been run from this working session.

## Delivered contract

Definitions can opt into `dice@1`, `cards@1`, `resources@1`, `effects@1` and `queues@1`.
Each module supplies typed operations/queries through the existing compiler, interpreter
and authenticated command API. Names, pools, faces, costs, token meanings, timing and
expiration policies are definition data. No game-name dispatch, universal defense
reduction or forced-hit rule exists in this path.

Core-only definitions retain **`rules-compiler-3` / `rules-runtime-2`**, their fingerprints
and checkpoint JSON shape. Mechanical definitions use **`rules-compiler-4` /
`rules-runtime-3`**. The runtime reads both contracts explicitly; old captured source is
not rewritten. V37 strengthens atomic saves with random-stream identity/cursor guards.

## Run the example

```text
mvn -q -pl engine-core -Prules-play "-Drules.source=engine-core/src/main/resources/rules/examples/dice-duel.json" "-Drules.script=engine-core/src/main/resources/rules/scenarios/dice-duel-play.json" compile exec:java
```

[`dice-duel.json`](engine-core/src/main/resources/rules/examples/dice-duel.json) implements
private order planning, committed queue reveal, an optional card play, dice rolling and
bonus dice, player-selected rerolls, defender token use, damage application and victory.
It uses the same procedure runtime/API as resource race and hidden grid. The small die
table deliberately gives each face the same total score so its demonstration script
has a predictable ending despite varying faces; independent fixtures include genuine
misses and variable numeric/symbolic outcomes.

Change `state.guardPolicy.initial` from `halve` to `cancel` to give the same `guard`
token a different effect using JSON alone. Tests use a larger health limit to compare
the different damage results. The example is a conformance/demo game, not an Armada
rules implementation or a balance model.

## Dice and result tables

An opted-in definition declares dice and result tables:

```json
{
  "dice": {
    "combat": {
      "maxRerolls": 1,
      "faces": [
        {"weight": 2, "value": 0, "symbols": []},
        {"weight": 1, "value": 1, "symbols": ["critical"]}
      ]
    }
  },
  "resultTables": {
    "damage": {"symbols":{"critical":2}, "valueMultiplier":1, "unmapped":"ERROR"}
  }
}
```

Positive integer face weights define the distribution. Values are signed integers;
symbol lists preserve multiplicity. Result tables sum uncancelled symbols and active
numeric values with checked arithmetic. `unmapped` is explicitly `ERROR` or `IGNORE`.
An empty pool resolves to zero; consecutive misses remain misses.

All mechanical statements use `op`, typed expression `args`, and `as` exactly when
they return a result. Pure queries use the existing `query` expression form.

| Operation | Inputs / result |
| --- | --- |
| `dice.roll` | `dice: list<string>`, `owner: player`, `visibility: string`; returns roll ID |
| `dice.add` | `roll`, `dice`; append newly rolled dice |
| `dice.remove` | `roll`, `indices`; mark selected dice removed without renumbering |
| `dice.reroll` | `roll`, `indices`, server-evaluated `allowed` indices, `maximum` |
| `dice.cancel` | `roll`, symbol multiset `symbols`, `maximum` |
| `dice.close` / `dice.clear` | Close modification, or remove a closed retained roll |
| `dice.indices` | Active, stable die indices |
| `dice.value`, `dice.type`, `dice.symbolsAt` | `roll`, `index`; per-die face data |
| `dice.symbols` | Aggregate symbols after cancellation |
| `dice.resolve` | `roll`, `table`; integer result |

Selections must be unique, active indices within their allowance. Each die also obeys
its type's reroll limit. Pool changes/rerolls precede cancellation; cancellation checks
that every requested symbol actually remains available. `symbolsAt` reports the raw
face symbols; cancellation affects the aggregate result. Rolls belong to the originating
procedure scope and close when it completes, even across nested reactions/decisions.
Closed results remain inspectable until the definition explicitly clears them.

## Durable randomness and recovery

The server creates a private 256-bit seed when it creates a mechanical session. A
versioned HMAC-SHA256 counter stream supplies bounded tickets using rejection sampling,
with a bounded rejection-work limit. The seed, algorithm and cursor are stored only in
authoritative `control.mechanics`; neither API creation nor commands accept a seed.

Random state is immutable and copied into the same isolated transition as costs and
effects. A rejected action or rolled-back SQL transaction commits no cursor advance.
Accepted outcomes, state and cursor are saved with the commit and idempotency receipt.
Resuming after a decision or retrying a committed command cannot roll again. Replay
applies the recorded commit without invoking the generator.

V37 rejects attempts to reseed, change algorithms/tapes, rewind the cursor, or attach
entropy to an existing core-only session. Runtime creation owns seed selection. Trusted
Java conformance tests can instead supply a fixed seed or explicit bounded ticket tape;
these hooks are not HTTP features.

Roll visibility can be PUBLIC, OWNER or SERVER. Public/player views include permitted
outcomes, never entropy, the stream cursor, execution scope or internal usage ledgers.
As in prior milestones, visibility protects content, not all deductions from deliberately
public results or activity counts.

## Cards and piles

`decks` declares card type codes (duplicates permitted), initial shuffle policy,
`exhaustion: REJECT|PARTIAL|RESHUFFLE`, and discard visibility. Each created physical
card receives a unique identity and stays in exactly one pile. Card type names do not
execute code; procedures interpret them through declared rules.

| Operation | Inputs / result |
| --- | --- |
| `cards.create` | `deck` definition, `owner`, `visibility`; creates deck + discard, returns deck ID |
| `cards.hand` | `owner`, `visibility`; creates empty hand, returns pile ID |
| `cards.draw` | `deck`, `to` hand, `count`; returns drawn card IDs |
| `cards.discard` | `cards`, `from` hand, originating `deck` |
| `cards.move` | `cards`, `from`, `to`; hand-to-hand movement |
| `cards.shuffle` | `deck`; Fisher–Yates using the same committed stream |
| `cards.pile`, `cards.type` | Pure pile contents / card type queries |

Reject exhaustion is atomic. Partial draws return what remains. Reshuffle draws consume
remaining deck cards first, then shuffle the originating discard when necessary; an
insufficient combined supply rejects. Wrong-pile, duplicate and wrong-deck discards
are rejected. Restore checks identity, pile membership and exact deck composition.
Views expose only permitted piles/cards. Revealing a public discard is an explicit
definition policy; hidden hand/deck contents are not exposed to spectators/admins.

## Resource and token lifecycles

Ready, exhausted, spent and banked tokens are ordinary declared bounded resources.
Definitions give those resources names and meanings and decide when operations run.

- `resources.transfer`: `from`, `fromResource`, `to`, `toResource`, `amount`; conserves
  counts, rejects self-transfer and enforces both bounds.
- `resources.convert`: `entity`, `from`, `to`, `spend`, `gain`; explicit exchange ratio.
- `resources.reset`: `entity`, `resource`, `value`; bounded assignment for declared refresh.
- `resources.once` / pure `resources.used`: `entity`, `key`, `period` (`ACTION`,
  `ACTIVATION`, `ROUND`); enforce explicit once-per-period effect use.

Combined costs can be sequenced in one procedure. A later failure rolls back all work
in that submission, including usage markers and entropy. Costs already committed before
a prior player decision retain the established M3 transaction boundary. Resource refresh
is a declared procedure (for example a phase entry), not an automatic universal refill.

## Timed effects

`effects` declares `duration`, `stacking`, `maximumStacks`, and visibility per named
effect type. `effects.apply(entity,key,value)` returns an effect ID; `effects.remove(id)`
removes it. `effects.total(entity,key)` and `effects.ids(entity,key)` are pure queries.

- **ACTION:** expires when the originating root procedure finishes, including its reactions.
- **ACTIVATION:** survives separate actions until the initiating player's/entity's
  activation ends. This differs from action scope in multi-step/multi-action turns.
- **ROUND:** expires on round change.
- **PERSISTENT:** remains until explicitly removed or its target is removed.

Stacking is ADD, MAX or REPLACE. Bounds and numeric overflow are checked. Effect values
do not secretly alter weapons or movement: definitions query them when building pools,
calculating costs or resolving effects. Owner-only effects require a player-owned target.
Transient effects also expire when the game ends. Usage markers follow their selected
period; the marker prevents reuse of the same entity/key until that period completes.

## Queued orders

`queues.plan(entity,field,capacity,orders,allowed)` fills exactly the uncommitted suffix
of a declared string-list attribute. `queues.commit(entity,field)` locks the current
prefix; `queues.reveal(entity,field)` consumes the first committed entry and returns it.
Capacity, available order names and invocation timing are expressions/procedures in the
definition. Visibility follows the attribute, supporting private or public queues.
Committed prefixes cannot be rewritten through planning. Refill appends to the tail.

The duel example connects planning phase → commit entry procedure → reveal during attack
→ timed effect/token procedure. Legacy `OrderEngine` sessions are not silently migrated.

## Verification

```text
mvn -B "-Prules-full,rules-mutation" clean verify
```

Conformance fixtures cover exact weighted faces, numeric/symbolic results, all-miss
pools, pool changes, invalid rerolls/cancellations, rollback of combined costs, private
cards, conservation/reshuffle/exhaustion, effect stacking/lifetimes, private queue refill
and malformed mechanical checkpoints. The existing coverage/mutation gates include the
new handlers and stream.

The mandatory `mechanics-recovery.cjs` suite additionally checks:

- A SQL failure after rolling leaves the persisted cursor and prior checkpoint unchanged.
- Successful retry produces the exact tickets independently calculated from the test
  database's private seed/counter; no seed is sent through an API.
- Preview and duplicate retries do not advance randomness.
- An actual application kill after a committed roll preserves its outcomes.
- An actual kill inside an uncommitted reroll rolls back both cursor and token payment;
  retry applies the same reroll and exactly one payment.
- Player/spectator/admin/history/event routes never expose entropy or another hand.
- SQL anti-reseed/rewind checks and historical outcomes remain valid after game completion.

Spatial mechanics and complete contrasting battle systems are now delivered in M5;
see `RULES_SPATIAL_M5.md`. The legacy battlefield
UI/catalogue migration remains later work; these mechanics are available through the
interpreted API and headless runner.
