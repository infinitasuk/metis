# Queued orders and reusable effects (F07)

## Delivered behavior

An optional `turnStructure.orders` module supplies per-unit private FIFO queues,
reveal/conversion windows, public token banks, order/token combinations and reusable
effects. Definitions without this configuration retain their existing sequencing.
Names, phase bindings, capacities, timing, costs, yaw limits and coordinated-unit
eligibility are definition data. The engine contains no game-identity checks.

V35 enables this module on matching existing catalogue configurations **for new
captures**. Start a new battle or explicitly restart into a separate battle to use
it. Previously captured definitions and source histories are retained.

## Configuration

`OrderRules` is nested under `TurnStructure`; edit it through the existing draft
turn-structure API. `V35__queued_orders.sql` is a complete catalogue example.

Required fields:

- `planningPhase`: a manual phase; all eligible living units must fill their queues.
- `activationPhase`: an entity-activation phase; explicit activation reveals the
  first committed entry before ordinary actions are allowed.
- `classifications`: eligible profile classifications; an empty list permits all.
- `capacity`, `tokenCapacity`: `Amount` objects. `{"value":2}` is constant;
  `{"stat":"engineeringValue","divisor":2,"roundUp":true}` resolves a profile
  binding. Supported adapter fields are `commandValue`, `engineeringValue`,
  `squadronValue`, `maximumSpeed`, `maximumHullPoints`. Missing divisor means 1.
- `perTypeLimit`: positive maximum token count for one order code.
- `orders`: unique `code`, display `label`, `timing`, `orderEffects`, `tokenEffects`.

These stat fields use the current typed profile adapter, rather than claiming a
universal characteristic model. Different names and constants can use the same
module; no command mechanics are implied merely by a game's name.

### Effects

Each effect has `kind`, `amount` and optional `parameters`. Matching kinds combine
their amounts when both sources are spent; their policies must match.

| Kind | Timing | Parameters / submitted choices |
| --- | --- | --- |
| `CHANGE_SPEED` | `BEFORE_MOVE` | Submit integer `speedDelta`; bounded by allowance, zero and profile maximum |
| `EXTRA_YAW` | `BEFORE_MOVE` | Policy `minimumSpeed`, optional `maximumClicks`; submit `yawJoint` (zero-based) |
| `RESTORE` | `ON_REVEAL` | Policy `costs` for `health`, `shield`, `transfer`; submit `operations` |
| `ADD_ATTACK_DICE` | `BEFORE_ATTACK` | Submit `color` or `auto`; explicit color must exist in the chosen attack pool |
| `REROLL_ATTACK_DICE` | `BEFORE_ATTACK` | Current adapter automatically rerolls misses, up to the allowance |
| `GRANT_ACTIVATIONS` | `ON_REVEAL` | Policy `range`, `classifications`, `actions`, optional `useMaximumSpeed` |

Restoration operations are positive integer amounts, for example:

```json
[
  {"resource":"health","amount":1},
  {"resource":"shield","amount":1,"zone":"front"},
  {"resource":"transfer","amount":1,"from":"rear","to":"left"}
]
```

The combined cost must fit the budget; health and shields cannot exceed their
profile maxima. Transfers conserve shields. Rejected operations consume nothing.

Attack modifiers apply to the next attack, including a second permitted attack;
they persist across a pending response and are consumed once on resolution.
Resolving a movement order advances to the movement step, forfeiting earlier
optional attacks. Movement editing/reset retains an already-paid yaw modifier and
does not refund or resolve the order again. In order-controlled activations the
speed selector cannot change speed without an effect; speed zero has zero travel.

## Action API

Use the existing `POST /api/v1/sessions/{id}/actions` envelope with `type`,
`actorPlayerId`, `expectedStateVersion`, and `parameters`. Authentication binds the
actor to an owned human seat. CPUs use the same engine actions.

| Action | Parameters |
| --- | --- |
| `plan_orders` | `unitId`, `orders`: exactly the uncommitted suffix, next-first |
| `activate_unit` | `unitId`; reveals and removes the first committed order |
| `store_order` | `unitId`; `replaceToken` required if the bank is full |
| `discard_order` | `unitId` |
| `resolve_order` | `unitId`, `orderCode`, `source` (`order`, `token`, `both`), effect choices |
| `activate_support_unit` | `unitId` of an eligible friendly unit |
| `end_support_activation` | No parameters |
| `end_order_grant` | No parameters; finish child activation first |

The planning suffix may be edited until phase completion. Existing committed
entries cannot be replaced or reordered; later rounds refill only the tail.
Banking is allowed immediately after reveal, before ordinary actions or resolving
an order. Banks enforce total capacity and per-type limits. Each order type can
resolve once per unit per round; combined use consumes both sources atomically.
Unused revealed orders and transient modifiers expire at activation completion;
banked tokens persist.

`GRANT_ACTIVATIONS` temporarily suspends the parent's actions. Nearby eligible
friendly units receive each configured action once (`move_unit`, `attack_unit`
are supported), without spending the parent's budget. Completed children are
marked spent for the round and skipped in their later ordinary phase. Human
movement remains editable until another child action or completion. Grants cannot
nest into another order-bearing unit. Response windows also work for child attacks.

## Privacy, UI, CPU and replay

- `values.orderQueues[unitId]` stores an owner-scoped envelope on the server.
  Only the owning seat receives the queue and its planning event; admin/spectator
  status grants no private view. Legal-action planning parameters are seat-filtered.
- `orderState` contains revealed orders, token banks and active effect/grant data.
  `orderActivations` tracks spent child activations. Normal unit visibility still
  applies to both. Client setup options cannot inject these protocol fields.
- The UI renders queue slots, committed prefixes, conversion/replacement, effect
  choices, restoration operations and support controls from legal actions. Private
  drafts survive another player's updates. Replay reconstructs public order state
  and the viewer's own historical queue instead of displaying the final queue at
  every earlier event.
- CPU planning uses its own projected view, prefers useful restoration or an
  attack modifier, and supplies only its own missing plans. It handles effects,
  support activations and responses through the authoritative engine. Strategy is
  deliberately basic; broader tactical policy remains F19.

## Current mechanical bounds

This completes the queued-order foundation, not full Armada rules conformance.
Restoration currently restores numeric health/shields, not individual damage cards.
Attack modifiers use the existing simplified dice adapter: rerolls are automatic
miss rerolls, not post-roll player-selected dice; the existing forced-hit behavior
is still pending F10. Response choices still occur before the roll. Grant range
uses center-to-center board distance (the catalogue currently configures 300),
without engagement/keyword exceptions. Direct dragging and maneuver geometry still
need F08/F09 unification; heading/overlap behavior remains the earlier approximation.
Card-triggered commands, special storage and full refresh policies remain F17/F18.

## Verification

- `mvn -q test`: queues, ownership projection, locked replenishment, timing,
  capacity/replacement/type limits, combined sources, restoration budgets,
  second-attack modifiers, paid-yaw editing/reset, support budgets/phase skipping,
  exactly-once response resumption, CPU order activation and existing regressions.
- `webapp/src/test/js/orders-integration.cjs`: four-user HTTP privacy, readiness,
  actor authorization, reveal, attack response/modifier consumption, token banking,
  immutable restart source, and CPU planning/resolution/handover.
- Optional Playwright checks exercise the real browser's private planning,
  opponent-poll draft preservation and order resolution, and fail on page errors.

Build `docker-app`, then use the isolated environment procedure in
`MULTIPLAYER_CONTRACT.md`, substituting names `metis-f07-db` / `metis-f07-app`.
The shared `multiplayer_fixtures.sql` now includes the synthetic orders definition.

```powershell
$env:TEST_DB_CONTAINER='metis-f07-db'
node webapp/src/test/js/multiplayer-integration.cjs
node webapp/src/test/js/orders-integration.cjs
# Optional: absolute path to an installed Playwright module; default browser is Edge.
$env:PLAYWRIGHT_MODULE='C:\path\to\node_modules\playwright'
$env:TEST_BROWSER_CHANNEL='msedge'
node webapp/src/test/js/orders-integration.cjs
```

The scripts delete their test sessions. Stop the temporary `--rm` app/database
containers afterward. Fixture SQL is for isolated test databases only.
