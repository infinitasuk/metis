-- Catalogue rules upgrade for new captures only; existing snapshots are immutable.
-- Names, profile bindings, timings, costs, limits and grant policy are definition data.
ALTER TABLE game_engine.game_version DISABLE TRIGGER turn_structure_immutable_trg;
UPDATE game_engine.game_version SET turn_structure = turn_structure || '{
 "orders":{
  "planningPhase":"command","activationPhase":"ship","classifications":["Ship"],
  "capacity":{"stat":"commandValue"},"tokenCapacity":{"stat":"commandValue"},"perTypeLimit":1,
  "orders":[
   {"code":"navigate","label":"Navigate","timing":"BEFORE_MOVE",
    "orderEffects":[{"kind":"CHANGE_SPEED","amount":{"value":1}},
                    {"kind":"EXTRA_YAW","amount":{"value":1},"parameters":{"minimumSpeed":1,"maximumClicks":2}}],
    "tokenEffects":[{"kind":"CHANGE_SPEED","amount":{"value":1}}]},
   {"code":"repair","label":"Repair","timing":"ON_REVEAL",
    "orderEffects":[{"kind":"RESTORE","amount":{"stat":"engineeringValue"},"parameters":{"costs":{"health":3,"shield":2,"transfer":1}}}],
    "tokenEffects":[{"kind":"RESTORE","amount":{"stat":"engineeringValue","divisor":2,"roundUp":true},"parameters":{"costs":{"health":3,"shield":2,"transfer":1}}}]},
   {"code":"concentrate_fire","label":"Concentrate fire","timing":"BEFORE_ATTACK",
    "orderEffects":[{"kind":"ADD_ATTACK_DICE","amount":{"value":1}}],
    "tokenEffects":[{"kind":"REROLL_ATTACK_DICE","amount":{"value":1}}]},
   {"code":"squadron","label":"Squadron","timing":"ON_REVEAL",
    "orderEffects":[{"kind":"GRANT_ACTIVATIONS","amount":{"stat":"squadronValue"},"parameters":{"range":300,"classifications":["Squadron"],"actions":["move_unit","attack_unit"],"useMaximumSpeed":true}}],
    "tokenEffects":[{"kind":"GRANT_ACTIVATIONS","amount":{"value":1},"parameters":{"range":300,"classifications":["Squadron"],"actions":["move_unit","attack_unit"],"useMaximumSpeed":true}}]}
  ]
 }
}'::jsonb
WHERE turn_structure IS NOT NULL AND NOT (turn_structure ? 'orders')
 AND profile_mapping ? 'commandCapacity'
 AND turn_structure->'phases' @> '[{"code":"command","mode":"MANUAL"},{"code":"ship","mode":"ALTERNATING_UNITS","classifications":["Ship"]}]'::jsonb;
ALTER TABLE game_engine.game_version ENABLE TRIGGER turn_structure_immutable_trg;
