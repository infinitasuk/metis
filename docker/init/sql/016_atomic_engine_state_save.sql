-- Atomically persist an authoritative state transition and its event.

BEGIN;

ALTER TABLE game_instance.game_session
    ADD COLUMN IF NOT EXISTS state_version bigint NOT NULL DEFAULT 0;

UPDATE game_instance.game_session
SET state_version = COALESCE((state_json ->> 'stateVersion')::bigint, 0)
WHERE state_json ? 'stateVersion';

ALTER TABLE game_instance.game_session
    DROP CONSTRAINT IF EXISTS game_session_state_version_ck;
ALTER TABLE game_instance.game_session
    ADD CONSTRAINT game_session_state_version_ck CHECK (state_version >= 0);

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
    state_version bigint,
    engine_version text,
    api_version text,
    event_schema_version integer,
    created_at timestamptz,
    updated_at timestamptz
) LANGUAGE sql STABLE AS $$
    SELECT s.session_id, s.game_id, g.game_name, s.game_version_id,
           gv.version_code, s.game_mode_id, s.scenario_id, s.fleet_format_id,
           s.session_name, s.session_code, s.status, s.current_round,
           s.current_phase_id, s.state_json, s.state_version, s.engine_version,
           s.api_version, s.event_schema_version, s.created_at, s.updated_at
    FROM game_instance.game_session s
    JOIN game_engine.game g ON g.game_id = s.game_id
    LEFT JOIN game_engine.game_version gv ON gv.game_version_id = s.game_version_id
    WHERE s.session_id = p_session_id
$$;

CREATE OR REPLACE PROCEDURE game_instance.sp_engine_state_save(
    IN p_session_id bigint,
    IN p_expected_state_version bigint,
    IN p_status text,
    IN p_current_round integer,
    IN p_current_phase_id bigint,
    IN p_state_json text,
    IN p_event_type text,
    IN p_actor_player_id bigint,
    IN p_payload_json text
) LANGUAGE plpgsql AS $$
DECLARE
    v_event_sequence bigint;
BEGIN
    PERFORM pg_advisory_xact_lock(p_session_id);
    IF COALESCE((p_state_json::jsonb ->> 'stateVersion')::bigint, -1) <> p_expected_state_version + 1 THEN
        RAISE EXCEPTION 'State JSON version does not follow the expected version' USING ERRCODE = '22023';
    END IF;
    UPDATE game_instance.game_session
    SET status = p_status,
        current_round = p_current_round,
        current_phase_id = p_current_phase_id,
        state_json = p_state_json::jsonb,
        state_version = p_expected_state_version + 1,
        updated_at = now()
    WHERE session_id = p_session_id
      AND state_version = p_expected_state_version;
    IF NOT FOUND THEN
        IF EXISTS (SELECT 1 FROM game_instance.game_session WHERE session_id = p_session_id) THEN
            RAISE EXCEPTION 'Session % has changed since the state was read', p_session_id USING ERRCODE = '40001';
        END IF;
        RAISE EXCEPTION 'Session % does not exist', p_session_id USING ERRCODE = 'P0002';
    END IF;
    SELECT COALESCE(max(event_sequence), 0) + 1 INTO v_event_sequence
    FROM game_instance.session_event
    WHERE session_id = p_session_id;
    INSERT INTO game_instance.session_event (
        session_id, event_sequence, event_type, actor_player_id, payload_json
    ) VALUES (
        p_session_id, v_event_sequence, p_event_type, p_actor_player_id,
        COALESCE(p_payload_json::jsonb, '{}'::jsonb)
    );
END
$$;

COMMIT;
