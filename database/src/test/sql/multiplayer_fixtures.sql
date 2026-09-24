-- Integration fixtures for an isolated test database; not a production migration.
BEGIN;
DO $fixtures$
DECLARE g bigint; v bigint; f bigint; c bigint; t bigint; u bigint;
        health_type bigint; weapon_type bigint; resource_type bigint;
        kind text; side text; config jsonb;
BEGIN
 IF EXISTS(SELECT 1 FROM game_engine.game WHERE game_name LIKE 'F06 % fixture') THEN RAISE EXCEPTION 'Fixture games already exist'; END IF;
 INSERT INTO game_engine.characteristic_type(characteristic_type_name) VALUES('F06 Integrity') RETURNING characteristic_type_id INTO health_type;
 INSERT INTO game_engine.characteristic_type(characteristic_type_name) VALUES('F06 Output') RETURNING characteristic_type_id INTO weapon_type;
 INSERT INTO game_engine.characteristic_type(characteristic_type_name) VALUES('F06 Resources') RETURNING characteristic_type_id INTO resource_type;
  FOREACH kind IN ARRAY ARRAY['private','response','orders'] LOOP
  INSERT INTO game_engine.game(game_name,minimum_number_of_players,maximum_number_of_players,minimum_number_of_cpu,maximum_number_of_cpu)
  VALUES('F06 '||kind||' fixture',2,2,0,2) RETURNING game_id INTO g;
  config := '{"deploymentRequired":true,"phases":[
   {"code":"planning","label":"Planning","mode":"MANUAL","groupSize":1,"steps":[],"privateInputs":[]},
   {"code":"engage","label":"Engagement","mode":"ALTERNATING_UNITS","groupSize":1,"steps":[
    {"code":"attack","label":"Attack","actions":["attack_unit"],"minimum":0,"maximum":1},
    {"code":"move","label":"Move","actions":["move_unit"],"minimum":1,"maximum":1,"editableMovement":true}]}
   ]}'::jsonb;
  config := config || jsonb_build_object('visibility',jsonb_build_object('units',CASE WHEN kind='private' THEN 'OWNER' ELSE 'PUBLIC' END,
                  'publicValues',jsonb_build_array('note','opaque'),'publicEvents','[]'::jsonb));
  IF kind='private' THEN
   config := jsonb_set(config,'{phases,0,privateInputs}','[{"code":"intent","label":"Intent","choices":["hold","advance"],"required":true,"revealAtEnd":false}]');
  ELSE
   config := config || '{"responses":[{"action":"attack_unit","targetUnitParameter":"targetUnitId","label":"Choose response","declineParameters":{"declineDefense":true},"choices":[],"resourceField":"readyDefenseTokens","resourceParameter":"defenseTokenType"}]}'::jsonb;
   END IF;
   IF kind='orders' THEN
    config := config || '{"orders":{"planningPhase":"planning","activationPhase":"engage","capacity":{"value":2},"tokenCapacity":{"value":1},"perTypeLimit":1,
     "orders":[
      {"code":"thrust","label":"Thrust","timing":"BEFORE_MOVE","orderEffects":[{"kind":"CHANGE_SPEED","amount":{"value":1}}],"tokenEffects":[{"kind":"CHANGE_SPEED","amount":{"value":1}}]},
      {"code":"focus","label":"Focus","timing":"BEFORE_ATTACK","orderEffects":[{"kind":"ADD_ATTACK_DICE","amount":{"value":1}}],"tokenEffects":[{"kind":"REROLL_ATTACK_DICE","amount":{"value":1}}]},
      {"code":"mend","label":"Mend","timing":"ON_REVEAL","orderEffects":[{"kind":"RESTORE","amount":{"value":3},"parameters":{"costs":{"health":3}}}],"tokenEffects":[{"kind":"RESTORE","amount":{"value":3},"parameters":{"costs":{"health":3}}}]}
     ]}}'::jsonb;
   END IF;
  INSERT INTO game_engine.game_version(game_id,version_code,version_name,lifecycle_status,profile_mapping,turn_structure)
  VALUES(g,'test','Test','published','{
   "health":{"sources":["F06 Integrity"],"required":true},"movement":{"default":1},"attackRange":{"default":300},
   "weapons":{"front":{"sources":["F06 Output"]}},"defenseTokens":{"sources":["F06 Resources"]},"baseRadius":10
  }',config) RETURNING game_version_id INTO v;
  FOREACH side IN ARRAY ARRAY['A','B'] LOOP
   INSERT INTO game_engine.faction(game_id,game_version_id,faction_name) VALUES(g,v,side) RETURNING faction_id INTO f;
   INSERT INTO game_engine.unit_classification(faction_id,unit_classification_name) VALUES(f,'Entity') RETURNING unit_classification_id INTO c;
   INSERT INTO game_engine.unit_type(unit_classification_id,unit_type_name) VALUES(c,'Candidate') RETURNING unit_type_id INTO t;
   INSERT INTO game_engine.unit_variant(unit_type_id,unit_variant_name) VALUES(t,'Candidate '||side) RETURNING unit_variant_id INTO u;
   INSERT INTO game_engine.unit_variant_characteristic(unit_variant_id,characteristic_type_id,characteristic_json) VALUES
    (u,health_type,'{"value":7}'),(u,weapon_type,'{"value":[{"color":"red","count":1}]}'),(u,resource_type,'{"value":[{"name":"guard","count":1}]}');
  END LOOP;
 END LOOP;
END $fixtures$;
COMMIT;
