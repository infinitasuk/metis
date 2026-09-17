-- Add playable Armada formats and the runtime defaults used by the simulator.

BEGIN;

UPDATE game_engine.game_mode
SET game_mode_name = 'Standard Battle',
    game_mode_description = 'The official 400-point format on a 6 ft x 3 ft play area. Ends after six rounds unless a fleet is destroyed.',
    mode_config_json = '{"mode_type":"standard_battle","points":400,"board":{"width":1800,"height":900},"maximumMovementDistance":100,"roundLimit":6,"phases":["command","ship","squadron","status"]}'::jsonb
WHERE game_mode_code = 'standard_battle'
  AND game_version_id = (
      SELECT gv.game_version_id
      FROM game_engine.game_version gv
      JOIN game_engine.game g ON g.game_id = gv.game_id
      WHERE g.game_name = 'Star Wars: Armada'
        AND gv.version_code = 'armada_1_5'
        AND gv.lifecycle_status = 'published'
  );

INSERT INTO game_engine.game_mode
    (game_id, game_version_id, game_mode_code, game_mode_name, game_mode_description, mode_config_json)
SELECT g.game_id, gv.game_version_id, mode_code, mode_name, mode_description, mode_config::jsonb
FROM game_engine.game g
JOIN game_engine.game_version gv ON gv.game_id = g.game_id
    AND gv.version_code = 'armada_1_5'
    AND gv.lifecycle_status = 'published'
JOIN (VALUES
    ('task_force', 'Task Force', 'A fast 200-point battle on a 3 ft x 3 ft play area.',
     '{"mode_type":"task_force","points":200,"board":{"width":900,"height":900},"maximumMovementDistance":100,"roundLimit":6,"phases":["command","ship","squadron","status"]}'),
    ('sector_fleet', 'Sector Fleet', 'A large 600-point battle for fleets that need more room to maneuver.',
     '{"mode_type":"sector_fleet","points":600,"board":{"width":1800,"height":900},"maximumMovementDistance":100,"roundLimit":6,"phases":["command","ship","squadron","status"]}'),
    ('unlimited_deathmatch', 'Unlimited Deathmatch', 'A no-round-limit battle that ends when one side is destroyed.',
     '{"mode_type":"unlimited_deathmatch","points":400,"board":{"width":1800,"height":900},"maximumMovementDistance":100,"unlimitedRounds":true,"phases":["command","ship","squadron","status"]}'),
    ('campaign_battle', 'Campaign Battle', 'A narrative battle intended to sit inside a campaign sequence.',
     '{"mode_type":"campaign_battle","points":400,"board":{"width":1800,"height":900},"maximumMovementDistance":100,"roundLimit":6,"campaign":true,"phases":["command","ship","squadron","status"]}')
) AS modes(mode_code, mode_name, mode_description, mode_config) ON true
WHERE g.game_name = 'Star Wars: Armada'
ON CONFLICT (game_version_id, game_mode_code) DO UPDATE
SET game_mode_name = EXCLUDED.game_mode_name,
    game_mode_description = EXCLUDED.game_mode_description,
    mode_config_json = EXCLUDED.mode_config_json;

INSERT INTO game_engine.fleet_format
    (game_mode_id, format_code, format_name, points_limit, format_config_json)
SELECT gm.game_mode_id, modes.mode_code || '_fleet', modes.format_name, modes.points_limit, modes.format_config::jsonb
FROM game_engine.game_mode gm
JOIN (VALUES
    ('task_force', 'Task Force fleet', 200, '{"ship_limit":3,"squadron_limit":3}'),
    ('sector_fleet', 'Sector fleet', 600, '{"ship_limit":6,"squadron_limit":12}'),
    ('unlimited_deathmatch', 'Unlimited fleet', 400, '{"ship_limit":3,"squadron_limit":5}'),
    ('campaign_battle', 'Campaign fleet', 400, '{"ship_limit":3,"squadron_limit":5}')
) AS modes(mode_code, format_name, points_limit, format_config) ON modes.mode_code = gm.game_mode_code
WHERE gm.game_version_id = (
    SELECT gv.game_version_id
    FROM game_engine.game_version gv
    JOIN game_engine.game g ON g.game_id = gv.game_id
    WHERE g.game_name = 'Star Wars: Armada'
      AND gv.version_code = 'armada_1_5'
      AND gv.lifecycle_status = 'published'
)
ON CONFLICT (game_mode_id, format_code) DO UPDATE
SET format_name = EXCLUDED.format_name,
    points_limit = EXCLUDED.points_limit,
    format_config_json = EXCLUDED.format_config_json;

INSERT INTO game_engine.fleet_format_faction (fleet_format_id, faction_id)
SELECT ff.fleet_format_id, f.faction_id
FROM game_engine.fleet_format ff
JOIN game_engine.game_mode gm ON gm.game_mode_id = ff.game_mode_id
JOIN game_engine.faction f ON f.game_version_id = gm.game_version_id
WHERE gm.game_version_id = (
    SELECT gv.game_version_id
    FROM game_engine.game_version gv
    JOIN game_engine.game g ON g.game_id = gv.game_id
    WHERE g.game_name = 'Star Wars: Armada'
      AND gv.version_code = 'armada_1_5'
      AND gv.lifecycle_status = 'published'
)
  AND gm.game_mode_code IN ('task_force', 'sector_fleet', 'unlimited_deathmatch', 'campaign_battle')
  AND ff.format_code IN ('task_force_fleet', 'sector_fleet_fleet', 'unlimited_deathmatch_fleet', 'campaign_battle_fleet')
ON CONFLICT DO NOTHING;

COMMIT;
