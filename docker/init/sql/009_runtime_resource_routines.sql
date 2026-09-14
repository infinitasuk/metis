-- CRUD routines for runtime fleets, units, boards, turns and events.

BEGIN;

CREATE OR REPLACE FUNCTION game_instance.fn_session_fleets(p_session_id bigint)
RETURNS TABLE (
    fleet_id bigint,
    session_id bigint,
    session_player_id bigint,
    player_name text,
    fleet_format_id bigint,
    fleet_format_name text,
    fleet_name text,
    points_total integer,
    unit_count bigint,
    state_json jsonb
) LANGUAGE sql STABLE AS $$
    SELECT pf.fleet_id, sp.session_id, sp.session_player_id, sp.display_name,
           pf.fleet_format_id, ff.format_name, pf.fleet_name, pf.points_total,
           count(fu.unit_instance_id), pf.state_json
    FROM game_instance.player_fleet pf
    JOIN game_instance.session_player sp ON sp.session_player_id = pf.session_player_id
    LEFT JOIN game_engine.fleet_format ff ON ff.fleet_format_id = pf.fleet_format_id
    LEFT JOIN game_instance.fleet_unit fu ON fu.fleet_id = pf.fleet_id
    WHERE sp.session_id = p_session_id
    GROUP BY pf.fleet_id, sp.session_id, sp.session_player_id, sp.display_name,
             ff.format_name, pf.state_json
    ORDER BY sp.seat_number, pf.fleet_id
$$;

CREATE OR REPLACE FUNCTION game_instance.fn_fleet_units(p_fleet_id bigint)
RETURNS TABLE (
    unit_instance_id bigint,
    fleet_id bigint,
    unit_variant_id bigint,
    unit_variant_name text,
    unit_name text,
    position_x numeric,
    position_y numeric,
    facing numeric,
    unit_status text,
    state_json jsonb
) LANGUAGE sql STABLE AS $$
    SELECT fu.unit_instance_id, fu.fleet_id, fu.unit_variant_id,
           uv.unit_variant_name, fu.unit_name, fu.position_x, fu.position_y,
           fu.facing, fu.unit_status, fu.state_json
    FROM game_instance.fleet_unit fu
    JOIN game_engine.unit_variant uv ON uv.unit_variant_id = fu.unit_variant_id
    WHERE fu.fleet_id = p_fleet_id
    ORDER BY fu.unit_instance_id
$$;

CREATE OR REPLACE FUNCTION game_instance.fn_session_boards(p_session_id bigint)
RETURNS TABLE (
    session_board_id bigint,
    session_id bigint,
    board_definition_id bigint,
    board_code text,
    board_name text,
    board_width numeric,
    board_height numeric,
    board_role text,
    state_json jsonb
) LANGUAGE sql STABLE AS $$
    SELECT sb.session_board_id, sb.session_id, sb.board_definition_id,
           bd.board_code, bd.board_name, bd.width, bd.height, sb.board_role,
           sb.state_json
    FROM game_instance.session_board sb
    JOIN game_engine.board_definition bd ON bd.board_definition_id = sb.board_definition_id
    WHERE sb.session_id = p_session_id
    ORDER BY sb.session_board_id
$$;

CREATE OR REPLACE FUNCTION game_instance.fn_session_turns(p_session_id bigint)
RETURNS TABLE (
    turn_id bigint,
    session_id bigint,
    round_number integer,
    phase_id bigint,
    phase_name text,
    active_player_id bigint,
    turn_sequence integer,
    started_at timestamptz,
    ended_at timestamptz,
    state_json jsonb
) LANGUAGE sql STABLE AS $$
    SELECT st.turn_id, st.session_id, st.round_number, st.phase_id,
           pd.phase_name, st.active_player_id, st.turn_sequence,
           st.started_at, st.ended_at, st.state_json
    FROM game_instance.session_turn st
    LEFT JOIN game_engine.phase_definition pd ON pd.phase_id = st.phase_id
    WHERE st.session_id = p_session_id
    ORDER BY st.turn_sequence
$$;

CREATE OR REPLACE FUNCTION game_instance.fn_event_get(p_event_id bigint)
RETURNS TABLE (
    event_id bigint,
    session_id bigint,
    event_sequence bigint,
    event_type text,
    actor_player_id bigint,
    payload_json jsonb,
    created_at timestamptz
) LANGUAGE sql STABLE AS $$
    SELECT event_id, session_id, event_sequence, event_type,
           actor_player_id, payload_json, created_at
    FROM game_instance.session_event
    WHERE event_id = p_event_id
$$;

CREATE OR REPLACE PROCEDURE game_instance.sp_fleet_create(
    IN p_session_player_id bigint,
    IN p_fleet_format_id bigint,
    IN p_fleet_name text,
    IN p_points_total integer,
    INOUT p_fleet_id bigint
) LANGUAGE plpgsql AS $$
DECLARE
    v_game_id bigint;
BEGIN
    SELECT s.game_id INTO v_game_id
    FROM game_instance.session_player sp
    JOIN game_instance.game_session s ON s.session_id = sp.session_id
    WHERE sp.session_player_id = p_session_player_id;
    IF v_game_id IS NULL THEN
        RAISE EXCEPTION 'Player % does not exist', p_session_player_id USING ERRCODE = '23503';
    END IF;
    IF p_fleet_format_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM game_engine.fleet_format ff
        JOIN game_engine.game_mode gm ON gm.game_mode_id = ff.game_mode_id
        WHERE ff.fleet_format_id = p_fleet_format_id AND gm.game_id = v_game_id
    ) THEN
        RAISE EXCEPTION 'Fleet format % does not belong to game %', p_fleet_format_id, v_game_id USING ERRCODE = '23503';
    END IF;
    INSERT INTO game_instance.player_fleet (session_player_id, fleet_format_id, fleet_name, points_total)
    VALUES (p_session_player_id, p_fleet_format_id, p_fleet_name, p_points_total)
    RETURNING fleet_id INTO p_fleet_id;
END
$$;

CREATE OR REPLACE PROCEDURE game_instance.sp_fleet_update(
    IN p_fleet_id bigint,
    IN p_fleet_name text,
    IN p_points_total integer,
    IN p_state_json text
) LANGUAGE plpgsql AS $$
BEGIN
    UPDATE game_instance.player_fleet
    SET fleet_name = COALESCE(p_fleet_name, fleet_name),
        points_total = COALESCE(p_points_total, points_total),
        state_json = COALESCE(p_state_json::jsonb, state_json)
    WHERE fleet_id = p_fleet_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Fleet % does not exist', p_fleet_id USING ERRCODE = 'P0002'; END IF;
END
$$;

CREATE OR REPLACE PROCEDURE game_instance.sp_fleet_delete(IN p_fleet_id bigint)
LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM game_instance.player_fleet WHERE fleet_id = p_fleet_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Fleet % does not exist', p_fleet_id USING ERRCODE = 'P0002'; END IF;
END
$$;

CREATE OR REPLACE PROCEDURE game_instance.sp_unit_create(
    IN p_fleet_id bigint,
    IN p_unit_variant_id bigint,
    IN p_unit_name text,
    IN p_position_x numeric,
    IN p_position_y numeric,
    IN p_facing numeric,
    IN p_unit_status text,
    IN p_state_json text,
    INOUT p_unit_instance_id bigint
) LANGUAGE plpgsql AS $$
DECLARE
    v_game_id bigint;
BEGIN
    SELECT s.game_id INTO v_game_id
    FROM game_instance.player_fleet pf
    JOIN game_instance.session_player sp ON sp.session_player_id = pf.session_player_id
    JOIN game_instance.game_session s ON s.session_id = sp.session_id
    WHERE pf.fleet_id = p_fleet_id;
    IF v_game_id IS NULL THEN
        RAISE EXCEPTION 'Fleet % does not exist', p_fleet_id USING ERRCODE = '23503';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM game_engine.unit_variant uv
        JOIN game_engine.unit_type ut ON ut.unit_type_id = uv.unit_type_id
        JOIN game_engine.unit_classification uc ON uc.unit_classification_id = ut.unit_classification_id
        JOIN game_engine.faction f ON f.faction_id = uc.faction_id
        WHERE uv.unit_variant_id = p_unit_variant_id AND f.game_id = v_game_id
    ) THEN
        RAISE EXCEPTION 'Unit variant % does not belong to game %', p_unit_variant_id, v_game_id USING ERRCODE = '23503';
    END IF;
    INSERT INTO game_instance.fleet_unit (
        fleet_id, unit_variant_id, unit_name, position_x, position_y, facing,
        unit_status, state_json
    ) VALUES (
        p_fleet_id, p_unit_variant_id, p_unit_name, p_position_x, p_position_y,
        p_facing, COALESCE(p_unit_status, 'ready'), COALESCE(p_state_json::jsonb, '{}'::jsonb)
    ) RETURNING unit_instance_id INTO p_unit_instance_id;
END
$$;

CREATE OR REPLACE PROCEDURE game_instance.sp_unit_update(
    IN p_unit_instance_id bigint,
    IN p_unit_name text,
    IN p_position_x numeric,
    IN p_position_y numeric,
    IN p_facing numeric,
    IN p_unit_status text,
    IN p_state_json text
) LANGUAGE plpgsql AS $$
BEGIN
    UPDATE game_instance.fleet_unit
    SET unit_name = COALESCE(p_unit_name, unit_name),
        position_x = COALESCE(p_position_x, position_x),
        position_y = COALESCE(p_position_y, position_y),
        facing = COALESCE(p_facing, facing),
        unit_status = COALESCE(p_unit_status, unit_status),
        state_json = COALESCE(p_state_json::jsonb, state_json)
    WHERE unit_instance_id = p_unit_instance_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Unit % does not exist', p_unit_instance_id USING ERRCODE = 'P0002'; END IF;
END
$$;

CREATE OR REPLACE PROCEDURE game_instance.sp_unit_delete(IN p_unit_instance_id bigint)
LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM game_instance.fleet_unit WHERE unit_instance_id = p_unit_instance_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Unit % does not exist', p_unit_instance_id USING ERRCODE = 'P0002'; END IF;
END
$$;

CREATE OR REPLACE PROCEDURE game_instance.sp_board_create(
    IN p_session_id bigint,
    IN p_board_definition_id bigint,
    IN p_board_role text,
    IN p_state_json text,
    INOUT p_session_board_id bigint
) LANGUAGE plpgsql AS $$
DECLARE
    v_game_id bigint;
BEGIN
    SELECT game_id INTO v_game_id FROM game_instance.game_session WHERE session_id = p_session_id;
    IF v_game_id IS NULL THEN
        RAISE EXCEPTION 'Session % does not exist', p_session_id USING ERRCODE = '23503';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM game_engine.board_definition
        WHERE board_definition_id = p_board_definition_id AND game_id = v_game_id
    ) THEN
        RAISE EXCEPTION 'Board % does not belong to game %', p_board_definition_id, v_game_id USING ERRCODE = '23503';
    END IF;
    INSERT INTO game_instance.session_board (session_id, board_definition_id, board_role, state_json)
    VALUES (p_session_id, p_board_definition_id, COALESCE(p_board_role, 'primary'), COALESCE(p_state_json::jsonb, '{}'::jsonb))
    RETURNING session_board_id INTO p_session_board_id;
END
$$;

CREATE OR REPLACE PROCEDURE game_instance.sp_board_update(
    IN p_session_board_id bigint,
    IN p_board_role text,
    IN p_state_json text
) LANGUAGE plpgsql AS $$
BEGIN
    UPDATE game_instance.session_board
    SET board_role = COALESCE(p_board_role, board_role),
        state_json = COALESCE(p_state_json::jsonb, state_json)
    WHERE session_board_id = p_session_board_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Session board % does not exist', p_session_board_id USING ERRCODE = 'P0002'; END IF;
END
$$;

CREATE OR REPLACE PROCEDURE game_instance.sp_board_delete(IN p_session_board_id bigint)
LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM game_instance.session_board WHERE session_board_id = p_session_board_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Session board % does not exist', p_session_board_id USING ERRCODE = 'P0002'; END IF;
END
$$;

CREATE OR REPLACE PROCEDURE game_instance.sp_turn_create(
    IN p_session_id bigint,
    IN p_round_number integer,
    IN p_phase_id bigint,
    IN p_active_player_id bigint,
    INOUT p_turn_id bigint
) LANGUAGE plpgsql AS $$
DECLARE
    v_sequence integer;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM game_instance.game_session WHERE session_id = p_session_id) THEN
        RAISE EXCEPTION 'Session % does not exist', p_session_id USING ERRCODE = '23503';
    END IF;
    IF p_active_player_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM game_instance.session_player WHERE session_player_id = p_active_player_id AND session_id = p_session_id
    ) THEN
        RAISE EXCEPTION 'Player % does not belong to session %', p_active_player_id, p_session_id USING ERRCODE = '23503';
    END IF;
    PERFORM pg_advisory_xact_lock(p_session_id);
    SELECT COALESCE(max(turn_sequence), 0) + 1 INTO v_sequence
    FROM game_instance.session_turn WHERE session_id = p_session_id;
    INSERT INTO game_instance.session_turn (session_id, round_number, phase_id, active_player_id, turn_sequence)
    VALUES (p_session_id, p_round_number, p_phase_id, p_active_player_id, v_sequence)
    RETURNING turn_id INTO p_turn_id;
    UPDATE game_instance.game_session
    SET current_round = p_round_number, current_phase_id = p_phase_id,
        updated_at = now()
    WHERE session_id = p_session_id;
END
$$;

CREATE OR REPLACE PROCEDURE game_instance.sp_turn_update(
    IN p_turn_id bigint,
    IN p_ended boolean,
    IN p_state_json text
) LANGUAGE plpgsql AS $$
BEGIN
    UPDATE game_instance.session_turn
    SET ended_at = CASE WHEN p_ended THEN COALESCE(ended_at, now()) ELSE ended_at END,
        state_json = COALESCE(p_state_json::jsonb, state_json)
    WHERE turn_id = p_turn_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Turn % does not exist', p_turn_id USING ERRCODE = 'P0002'; END IF;
END
$$;

CREATE OR REPLACE PROCEDURE game_instance.sp_turn_delete(IN p_turn_id bigint)
LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM game_instance.session_turn WHERE turn_id = p_turn_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Turn % does not exist', p_turn_id USING ERRCODE = 'P0002'; END IF;
END
$$;

CREATE OR REPLACE PROCEDURE game_instance.sp_event_append(
    IN p_session_id bigint,
    IN p_event_type text,
    IN p_actor_player_id bigint,
    IN p_payload_json text,
    INOUT p_event_id bigint
) LANGUAGE plpgsql AS $$
DECLARE
    v_sequence bigint;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM game_instance.game_session WHERE session_id = p_session_id) THEN
        RAISE EXCEPTION 'Session % does not exist', p_session_id USING ERRCODE = '23503';
    END IF;
    PERFORM pg_advisory_xact_lock(p_session_id);
    SELECT COALESCE(max(event_sequence), 0) + 1 INTO v_sequence
    FROM game_instance.session_event WHERE session_id = p_session_id;
    INSERT INTO game_instance.session_event (
        session_id, event_sequence, event_type, actor_player_id, payload_json
    ) VALUES (
        p_session_id, v_sequence, p_event_type, p_actor_player_id,
        COALESCE(p_payload_json::jsonb, '{}'::jsonb)
    ) RETURNING event_id INTO p_event_id;
END
$$;

COMMIT;
