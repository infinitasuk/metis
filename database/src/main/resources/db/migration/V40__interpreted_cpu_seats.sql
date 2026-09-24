ALTER TABLE game_instance.rules_seat ADD COLUMN kind text NOT NULL DEFAULT 'HUMAN' CHECK(kind IN ('HUMAN','CPU'));
ALTER TABLE game_instance.rules_session
 ADD COLUMN cpu_limit integer NOT NULL DEFAULT 1000 CHECK(cpu_limit BETWEEN 1 AND 10000),
 ADD COLUMN cpu_steps integer NOT NULL DEFAULT 0 CHECK(cpu_steps>=0),
 ADD COLUMN cpu_checked_at timestamptz NOT NULL DEFAULT '-infinity',
 ADD COLUMN cpu_error text;
CREATE INDEX rules_cpu_queue ON game_instance.rules_session(cpu_checked_at) WHERE NOT paused AND status<>'FINISHED';
CREATE OR REPLACE FUNCTION game_instance.trg_rules_capture_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.definition_id,NEW.owner_user_id,NEW.visibility,NEW.captured_definition,NEW.fingerprint,NEW.compiler_version,NEW.runtime_version,NEW.modules_json,NEW.initial_checkpoint,NEW.captured_resolution,NEW.cpu_limit)
 IS DISTINCT FROM (OLD.definition_id,OLD.owner_user_id,OLD.visibility,OLD.captured_definition,OLD.fingerprint,OLD.compiler_version,OLD.runtime_version,OLD.modules_json,OLD.initial_checkpoint,OLD.captured_resolution,OLD.cpu_limit)
 THEN RAISE EXCEPTION 'Captured rules and membership context are immutable' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END $$;
CREATE PROCEDURE game_instance.sp_rules_session_create_v2(p_id uuid,p_definition bigint,p_owner bigint,p_visibility text,p_initial text,p_seats text,p_viewers text,p_cpu_limit integer) LANGUAGE plpgsql AS $$
DECLARE d game_engine.rules_definition%ROWTYPE; member jsonb; ordinal integer:=0; seat_kind text;
BEGIN
 SELECT * INTO STRICT d FROM game_engine.rules_definition WHERE definition_id=p_definition AND lifecycle='published' FOR SHARE;
 IF (p_initial::jsonb->>'version')::bigint IS DISTINCT FROM 0 OR p_initial::jsonb->'control'->>'status' IS DISTINCT FROM 'SETUP' THEN RAISE EXCEPTION 'Initial checkpoint required'; END IF;
 INSERT INTO game_instance.rules_session(session_id,definition_id,owner_user_id,visibility,captured_definition,fingerprint,compiler_version,runtime_version,modules_json,initial_checkpoint,checkpoint_json,status,captured_resolution,cpu_limit)
 VALUES(p_id,p_definition,p_owner,p_visibility,COALESCE(d.resolved_json,d.source_json),d.fingerprint,d.compiler_version,d.runtime_version,d.modules_json,p_initial,p_initial,'SETUP',d.resolution_json,p_cpu_limit);
 FOR member IN SELECT value FROM jsonb_array_elements(p_seats::jsonb) LOOP
  ordinal:=ordinal+1;seat_kind:=COALESCE(member->>'kind','HUMAN');
  IF NOT EXISTS(SELECT 1 FROM game_instance.app_user WHERE user_id=(member->>'userId')::bigint AND active)
     OR (seat_kind='CPU' AND (member->>'userId')::bigint IS DISTINCT FROM p_owner) THEN RAISE EXCEPTION 'Invalid seat principal'; END IF;
  INSERT INTO game_instance.rules_seat(session_id,player_id,seat_number,user_id,kind) VALUES(p_id,member->>'playerId',ordinal,(member->>'userId')::bigint,seat_kind);
 END LOOP;
 IF (SELECT jsonb_agg(player_id ORDER BY seat_number) FROM game_instance.rules_seat WHERE session_id=p_id) IS DISTINCT FROM p_initial::jsonb->'players' THEN RAISE EXCEPTION 'Seat/checkpoint mismatch'; END IF;
 FOR member IN SELECT value FROM jsonb_array_elements(p_viewers::jsonb) LOOP INSERT INTO game_instance.rules_viewer VALUES(p_id,(member#>>'{}')::bigint); END LOOP;
END $$;
CREATE FUNCTION game_instance.fn_rules_session_list(p_user bigint,p_admin boolean)
 RETURNS TABLE(session_id uuid,definition_id bigint,definition_key text,status text,paused boolean,state_version bigint,owner_user_id bigint,cpu_error text)
 LANGUAGE sql STABLE AS $$
 SELECT s.session_id,s.definition_id,d.definition_key,s.status,s.paused,s.state_version,s.owner_user_id,s.cpu_error
 FROM game_instance.rules_session s JOIN game_engine.rules_definition d USING(definition_id)
 WHERE game_instance.fn_rules_can_view(s.session_id,p_user,p_admin) ORDER BY s.updated_at DESC,s.session_id LIMIT 200
$$;
CREATE FUNCTION game_instance.fn_rules_cpu_queue() RETURNS TABLE(session_id uuid) LANGUAGE sql STABLE AS $$
 SELECT s.session_id FROM game_instance.rules_session s WHERE NOT s.paused AND s.status<>'FINISHED' AND s.state_version>0
 AND EXISTS(SELECT 1 FROM game_instance.rules_seat seat WHERE seat.session_id=s.session_id AND seat.kind='CPU')
 ORDER BY s.cpu_checked_at,s.session_id LIMIT 16
$$;
CREATE FUNCTION game_instance.fn_rules_workspace_stamp(p_id uuid,p_user bigint,p_admin boolean) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT s.session_id::text||':'||s.fingerprint||':'||s.runtime_version||':'||s.state_version||':'||s.paused||':'||p_user
 FROM game_instance.rules_session s WHERE s.session_id=p_id AND game_instance.fn_rules_can_view(s.session_id,p_user,p_admin)
$$;
CREATE FUNCTION game_instance.fn_rules_cpu_claim(p_id uuid) RETURNS SETOF game_instance.rules_session LANGUAGE sql VOLATILE AS $$
 SELECT * FROM game_instance.rules_session WHERE session_id=p_id AND NOT paused AND status<>'FINISHED' AND state_version>0 FOR UPDATE SKIP LOCKED
$$;
CREATE PROCEDURE game_instance.sp_rules_cpu_touch(p_id uuid,p_increment integer,p_reset boolean) LANGUAGE plpgsql AS $$
BEGIN
 UPDATE game_instance.rules_session SET cpu_checked_at=clock_timestamp(),cpu_steps=CASE WHEN p_reset THEN 0 ELSE cpu_steps+p_increment END,
 cpu_error=CASE WHEN p_reset THEN NULL ELSE cpu_error END WHERE session_id=p_id;
END $$;
-- Quarantine an unreadable capture without fabricating a game transition or outcome.
CREATE PROCEDURE game_instance.sp_rules_cpu_quarantine(p_id uuid,p_error text) LANGUAGE plpgsql AS $$
BEGIN UPDATE game_instance.rules_session SET paused=true,cpu_error=p_error,cpu_checked_at=clock_timestamp() WHERE session_id=p_id; END $$;
