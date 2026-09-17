-- Align retained CPU fixture sessions with the catalog and runtime tables.

BEGIN;

ALTER TABLE game_instance.game_session
    ADD COLUMN IF NOT EXISTS ai_version text,
    ADD COLUMN IF NOT EXISTS run_number integer NOT NULL DEFAULT 0;

ALTER TABLE game_instance.game_session
    DROP CONSTRAINT IF EXISTS game_session_run_number_ck;
ALTER TABLE game_instance.game_session
    ADD CONSTRAINT game_session_run_number_ck CHECK (run_number >= 0);

CREATE INDEX IF NOT EXISTS game_session_template_run_idx
    ON game_instance.game_session (template_id, run_number, updated_at DESC);

UPDATE game_instance.game_session s
SET game_mode_id = (
        SELECT gm.game_mode_id FROM game_engine.game_mode gm
        WHERE gm.game_version_id = s.game_version_id
          AND gm.game_mode_code = 'standard_battle'
        LIMIT 1
    ),
    scenario_id = (
        SELECT sc.scenario_id FROM game_engine.scenario_definition sc
        WHERE sc.game_version_id = s.game_version_id
          AND sc.scenario_code = 'standard_battle'
        LIMIT 1
    ),
    fleet_format_id = (
        SELECT ff.fleet_format_id
        FROM game_engine.fleet_format ff
        JOIN game_engine.game_mode gm ON gm.game_mode_id = ff.game_mode_id
        WHERE gm.game_version_id = s.game_version_id
          AND gm.game_mode_code = 'standard_battle'
          AND ff.format_code = 'standard_fleet'
        LIMIT 1
    ),
    ai_version = 'cpu-fixture-v1',
    run_number = 0,
    updated_at = now()
WHERE s.session_name LIKE 'METIS-TEST-GAME-%';

UPDATE game_instance.session_player sp
SET display_name = f.faction_name
FROM game_instance.game_session s, game_engine.faction f
WHERE sp.session_id = s.session_id
  AND f.faction_id = sp.faction_id
  AND s.session_name LIKE 'METIS-TEST-GAME-%';

INSERT INTO game_instance.session_board (session_id, board_definition_id, board_role)
SELECT s.session_id, bd.board_definition_id, 'primary'
FROM game_instance.game_session s
JOIN game_engine.board_definition bd
  ON bd.game_version_id = s.game_version_id AND bd.board_code = 'standard_6x3'
WHERE s.session_name LIKE 'METIS-TEST-GAME-%'
  AND NOT EXISTS (
      SELECT 1 FROM game_instance.session_board sb WHERE sb.session_id = s.session_id
  );

INSERT INTO game_instance.player_fleet (session_player_id, fleet_format_id, fleet_name, points_total)
SELECT sp.session_player_id, s.fleet_format_id, 'Fleet ' || sp.seat_number, 400
FROM game_instance.session_player sp
JOIN game_instance.game_session s ON s.session_id = sp.session_id
WHERE s.session_name LIKE 'METIS-TEST-GAME-%'
  AND NOT EXISTS (
      SELECT 1 FROM game_instance.player_fleet pf WHERE pf.session_player_id = sp.session_player_id
  );

INSERT INTO game_instance.fleet_unit (
    fleet_id, unit_variant_id, unit_name, position_x, position_y, facing, unit_status, state_json
)
SELECT pf.fleet_id,
       (unit->>'unitCode')::bigint,
       uv.unit_variant_name,
       (unit->>'positionX')::numeric,
       (unit->>'positionY')::numeric,
       (unit->>'facing')::numeric,
       COALESCE(unit->>'status', 'ready'),
       jsonb_build_object('engineUnitId', unit->>'unitId', 'unit', unit)
FROM game_instance.game_session s
CROSS JOIN LATERAL jsonb_array_elements(s.state_json->'units') unit
JOIN game_instance.session_player sp
  ON sp.session_player_id = (unit->>'playerId')::bigint
JOIN game_instance.player_fleet pf ON pf.session_player_id = sp.session_player_id
JOIN game_engine.unit_variant uv ON uv.unit_variant_id = (unit->>'unitCode')::bigint
WHERE s.session_name LIKE 'METIS-TEST-GAME-%'
  AND (unit->>'unitCode') ~ '^[0-9]+$'
  AND NOT EXISTS (
      SELECT 1
      FROM game_instance.fleet_unit existing
      WHERE existing.fleet_id = pf.fleet_id
        AND existing.state_json->>'engineUnitId' = unit->>'unitId'
  );

UPDATE game_instance.battle_template t
SET game_mode_id = s.game_mode_id,
    scenario_id = s.scenario_id,
    fleet_format_id = s.fleet_format_id,
    board_code = 'standard_6x3',
    board_width = 1800,
    board_height = 900,
    players_json = COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
            'seatNumber', sp.seat_number,
            'displayName', sp.display_name,
            'playerKind', sp.player_kind,
            'factionId', sp.faction_id,
            'factionName', f.faction_name,
            'fleetName', 'Fleet ' || sp.seat_number,
            'pointsTotal', 400
        ) ORDER BY sp.seat_number)
        FROM game_instance.session_player sp
        JOIN game_engine.faction f ON f.faction_id = sp.faction_id
        WHERE sp.session_id = s.session_id
    ), '[]'::jsonb),
    updated_at = now()
FROM game_instance.game_session s
WHERE t.template_session_id = s.session_id
  AND s.session_name LIKE 'METIS-TEST-GAME-%';

DROP FUNCTION IF EXISTS game_instance.fn_session_list(bigint, text);
CREATE OR REPLACE FUNCTION game_instance.fn_session_list(
    p_game_id bigint DEFAULT NULL,
    p_status text DEFAULT NULL
) RETURNS TABLE (
    session_id bigint, game_id bigint, game_name text, session_name text,
    session_code text, visibility text, owner_user_id bigint, template_id bigint,
    template_name text, ai_version text, run_number integer, status text,
    current_round integer, player_count bigint, created_at timestamptz, updated_at timestamptz
) LANGUAGE sql STABLE AS $$
    SELECT s.session_id, s.game_id, g.game_name, s.session_name, s.session_code,
           s.visibility, s.owner_user_id, s.template_id, t.template_name,
           s.ai_version, s.run_number, s.status, s.current_round,
           count(sp.session_player_id), s.created_at, s.updated_at
    FROM game_instance.game_session s
    JOIN game_engine.game g ON g.game_id = s.game_id
    LEFT JOIN game_instance.battle_template t ON t.template_id = s.template_id
    LEFT JOIN game_instance.session_player sp ON sp.session_id = s.session_id
    WHERE (p_game_id IS NULL OR s.game_id = p_game_id)
      AND (p_status IS NULL OR s.status = p_status)
    GROUP BY s.session_id, g.game_name, t.template_name
    ORDER BY s.updated_at DESC, s.session_id DESC
$$;

DROP FUNCTION IF EXISTS game_instance.fn_session_get(bigint);
CREATE OR REPLACE FUNCTION game_instance.fn_session_get(p_session_id bigint)
RETURNS TABLE (
    session_id bigint, game_id bigint, game_name text, game_version_id bigint,
    game_version_code text, game_mode_id bigint, scenario_id bigint, fleet_format_id bigint,
    session_name text, session_code text, visibility text, owner_user_id bigint,
    template_id bigint, template_name text, ai_version text, run_number integer,
    status text, current_round integer, current_phase_id bigint, state_json jsonb,
    state_version bigint, engine_version text, api_version text, event_schema_version integer,
    created_at timestamptz, updated_at timestamptz
) LANGUAGE sql STABLE AS $$
    SELECT s.session_id, s.game_id, g.game_name, s.game_version_id,
           gv.version_code, s.game_mode_id, s.scenario_id, s.fleet_format_id,
           s.session_name, s.session_code, s.visibility, s.owner_user_id,
           s.template_id, t.template_name, s.ai_version, s.run_number,
           s.status, s.current_round, s.current_phase_id, s.state_json,
           s.state_version, s.engine_version, s.api_version,
           s.event_schema_version, s.created_at, s.updated_at
    FROM game_instance.game_session s
    JOIN game_engine.game g ON g.game_id = s.game_id
    LEFT JOIN game_engine.game_version gv ON gv.game_version_id = s.game_version_id
    LEFT JOIN game_instance.battle_template t ON t.template_id = s.template_id
    WHERE s.session_id = p_session_id
$$;

COMMIT;
