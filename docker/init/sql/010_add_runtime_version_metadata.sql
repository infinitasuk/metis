-- Record the software and definition versions used by each simulation.

BEGIN;

ALTER TABLE game_instance.game_session
    ADD COLUMN IF NOT EXISTS engine_version text NOT NULL DEFAULT '0.1.0-SNAPSHOT',
    ADD COLUMN IF NOT EXISTS api_version text NOT NULL DEFAULT 'v1',
    ADD COLUMN IF NOT EXISTS event_schema_version integer NOT NULL DEFAULT 1;

DROP FUNCTION IF EXISTS game_instance.fn_session_get(bigint);
CREATE OR REPLACE FUNCTION game_instance.fn_session_get(p_session_id bigint)
RETURNS TABLE (
    session_id bigint,
    game_id bigint,
    game_name text,
    game_version_id bigint,
    game_version_code text,
    game_mode_id bigint,
    scenario_id bigint,
    fleet_format_id bigint,
    session_name text,
    session_code text,
    status text,
    current_round integer,
    current_phase_id bigint,
    state_json jsonb,
    engine_version text,
    api_version text,
    event_schema_version integer,
    created_at timestamptz,
    updated_at timestamptz
) LANGUAGE sql STABLE AS $$
    SELECT s.session_id, s.game_id, g.game_name, s.game_version_id,
           gv.version_code, s.game_mode_id, s.scenario_id, s.fleet_format_id,
           s.session_name, s.session_code, s.status, s.current_round,
           s.current_phase_id, s.state_json, s.engine_version, s.api_version,
           s.event_schema_version, s.created_at, s.updated_at
    FROM game_instance.game_session s
    JOIN game_engine.game g ON g.game_id = s.game_id
    LEFT JOIN game_engine.game_version gv ON gv.game_version_id = s.game_version_id
    WHERE s.session_id = p_session_id
$$;

DROP PROCEDURE IF EXISTS game_instance.sp_session_create(bigint, text, bigint, bigint, bigint, bigint);
DROP PROCEDURE IF EXISTS game_instance.sp_session_create(bigint, text, bigint, bigint, bigint, text, bigint);
CREATE OR REPLACE PROCEDURE game_instance.sp_session_create(
    IN p_game_id bigint,
    IN p_session_name text,
    IN p_game_mode_id bigint,
    IN p_scenario_id bigint,
    IN p_fleet_format_id bigint,
    IN p_engine_version text,
    IN p_api_version text,
    IN p_event_schema_version integer,
    INOUT p_session_id bigint
) LANGUAGE plpgsql AS $$
DECLARE
    v_code text;
    v_game_version_id bigint;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM game_engine.game WHERE game_id = p_game_id) THEN
        RAISE EXCEPTION 'Game % does not exist', p_game_id USING ERRCODE = '23503';
    END IF;
    IF p_game_mode_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM game_engine.game_mode WHERE game_mode_id = p_game_mode_id AND game_id = p_game_id
    ) THEN
        RAISE EXCEPTION 'Game mode % does not belong to game %', p_game_mode_id, p_game_id USING ERRCODE = '23503';
    END IF;
    IF p_scenario_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM game_engine.scenario_definition WHERE scenario_id = p_scenario_id AND game_id = p_game_id
    ) THEN
        RAISE EXCEPTION 'Scenario % does not belong to game %', p_scenario_id, p_game_id USING ERRCODE = '23503';
    END IF;
    IF p_fleet_format_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM game_engine.fleet_format ff
        JOIN game_engine.game_mode gm ON gm.game_mode_id = ff.game_mode_id
        WHERE ff.fleet_format_id = p_fleet_format_id AND gm.game_id = p_game_id
    ) THEN
        RAISE EXCEPTION 'Fleet format % does not belong to game %', p_fleet_format_id, p_game_id USING ERRCODE = '23503';
    END IF;
    SELECT game_version_id INTO v_game_version_id
    FROM game_engine.game_version
    WHERE game_id = p_game_id AND is_active
    ORDER BY release_date DESC NULLS LAST, game_version_id DESC
    LIMIT 1;
    v_code := 'SIM-' || to_char(clock_timestamp(), 'YYYYMMDDHH24MISSMS') || '-' || txid_current();
    INSERT INTO game_instance.game_session (
        game_id, game_version_id, game_mode_id, scenario_id, fleet_format_id,
        session_name, session_code, engine_version, api_version, event_schema_version
    ) VALUES (
        p_game_id, v_game_version_id, p_game_mode_id, p_scenario_id,
        p_fleet_format_id, p_session_name, v_code,
        COALESCE(p_engine_version, '0.1.0-SNAPSHOT'),
        COALESCE(p_api_version, 'v1'), COALESCE(p_event_schema_version, 1)
    ) RETURNING session_id INTO p_session_id;
END
$$;

COMMIT;
