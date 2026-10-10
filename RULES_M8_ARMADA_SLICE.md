# M8 Armada Setup Slice

M8 adds a new immutable `armada-squadron-training-m8` catalogue pack. It does not
rewrite the M7 pack or any captured session.

## Supported

- One definition-owned squadron per seat.
- Faction and roster-count validation through `scenario.roster`.
- Owner-specific deployment zones with bounded numeric placement choices.
- Footprint, board-edge, duplicate-placement and zone-owner validation through the
  shared spatial module.
- Alternating deployment followed by the existing alternating squadron activation
  controller.
- The same deployment and activation commands for human and CPU seats.
- Generic browser rendering of the deployment action without M8-specific client code.

The pack is imported with `armada-squadron-training-m8` and produces a standard,
close-engagement and short-battle entry. Existing `armada-squadron-training-v1`
entries remain unchanged.

## Cost Boundary

The current database projection does not expose canonical Armada fleet costs in the
interpreted catalogue snapshot. M8 therefore uses an explicit zero-valued,
development-derived cost field and a one-entry budget policy. This validates the
roster contract without claiming official fleet legality.

Canonical points, squadron caps, upgrades, ships, objectives and official ruler
calibration remain future slices. They must be imported with source provenance before
the pack can be described as a standard Armada fleet builder.

The follow-on `armada-squadron-points-v1` slice now captures sourced costs for
these two units and shares roster validation between preflight and setup. See
[RULES_SOURCED_POINTS.md](RULES_SOURCED_POINTS.md). The original M8 pack retains
its zero-cost contract; broader fleet formats remain future work.

## Acceptance

- Invalid roster faction and count inputs reject atomically.
- A player cannot deploy an opponent's entity or use the opponent's zone.
- A deployment footprint must remain inside its owner zone and board.
- A valid deployment advances to the alternating squadron activation phase.
- Existing M0-M7 suites, captures and session 139 remain unaffected.
