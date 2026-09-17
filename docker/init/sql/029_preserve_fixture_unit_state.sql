-- Preserve the full engine unit snapshot in relational fixture rows.

BEGIN;

UPDATE game_instance.fleet_unit fu
SET state_json = jsonb_build_object('engineUnitId', unit->>'unitId', 'unit', unit)
FROM game_instance.game_session s
CROSS JOIN LATERAL jsonb_array_elements(s.state_json->'units') unit
JOIN game_instance.session_player sp ON sp.session_player_id = (unit->>'playerId')::bigint
JOIN game_instance.player_fleet pf ON pf.session_player_id = sp.session_player_id
WHERE pf.fleet_id = fu.fleet_id
  AND s.session_name LIKE 'METIS-TEST-GAME-%'
  AND (fu.state_json->>'engineUnitId' = unit->>'unitId'
       OR fu.state_json->>'unitId' = unit->>'unitId');

COMMIT;
