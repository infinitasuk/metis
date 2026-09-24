-- New interpreted sessions are isolated from legacy battle snapshots and CPU runners.
CREATE TABLE game_engine.rules_definition (
 definition_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 definition_key text NOT NULL, revision text NOT NULL, edit_version bigint NOT NULL DEFAULT 0,
 lifecycle text NOT NULL DEFAULT 'draft' CHECK(lifecycle IN ('draft','published')),
 source_json text NOT NULL CHECK(jsonb_typeof(source_json::jsonb)='object'),
 fingerprint text, compiler_version text, runtime_version text, modules_json jsonb,
 created_by bigint NOT NULL REFERENCES game_instance.app_user(user_id),
 created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz,
 UNIQUE(definition_key,revision),
 CHECK(lifecycle='draft' OR (fingerprint IS NOT NULL AND compiler_version IS NOT NULL AND runtime_version IS NOT NULL AND modules_json IS NOT NULL))
);
CREATE FUNCTION game_engine.trg_rules_definition_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.lifecycle='published' THEN RAISE EXCEPTION 'Published rules are immutable' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER rules_definition_immutable BEFORE UPDATE ON game_engine.rules_definition FOR EACH ROW EXECUTE FUNCTION game_engine.trg_rules_definition_immutable();
CREATE FUNCTION game_engine.fn_rules_definition_get(p_id bigint) RETURNS SETOF game_engine.rules_definition LANGUAGE sql STABLE AS $$ SELECT * FROM game_engine.rules_definition WHERE definition_id=p_id $$;
CREATE FUNCTION game_engine.fn_rules_definition_lock(p_id bigint) RETURNS SETOF game_engine.rules_definition LANGUAGE sql VOLATILE AS $$ SELECT * FROM game_engine.rules_definition WHERE definition_id=p_id FOR UPDATE $$;
CREATE FUNCTION game_engine.fn_rules_definition_list() RETURNS SETOF game_engine.rules_definition LANGUAGE sql STABLE AS $$ SELECT * FROM game_engine.rules_definition ORDER BY definition_id $$;
CREATE PROCEDURE game_engine.sp_rules_definition_create(p_source text,p_user bigint,INOUT p_id bigint) LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO game_engine.rules_definition(definition_key,revision,source_json,created_by)
 VALUES(p_source::jsonb->>'id',p_source::jsonb->>'revision',p_source,p_user) RETURNING definition_id INTO p_id;
END $$;
CREATE PROCEDURE game_engine.sp_rules_definition_update(p_id bigint,p_version bigint,p_source text) LANGUAGE plpgsql AS $$
BEGIN
 UPDATE game_engine.rules_definition SET source_json=p_source,definition_key=p_source::jsonb->>'id',revision=p_source::jsonb->>'revision',edit_version=edit_version+1
 WHERE definition_id=p_id AND edit_version=p_version;
 IF NOT FOUND THEN RAISE EXCEPTION 'Definition version conflict' USING ERRCODE='40001'; END IF;
END $$;
CREATE PROCEDURE game_engine.sp_rules_definition_publish(p_id bigint,p_version bigint,p_source text,p_fingerprint text,p_compiler text,p_runtime text,p_modules text) LANGUAGE plpgsql AS $$
BEGIN
 UPDATE game_engine.rules_definition SET source_json=p_source,fingerprint=p_fingerprint,compiler_version=p_compiler,runtime_version=p_runtime,
 modules_json=p_modules::jsonb,lifecycle='published',published_at=now(),edit_version=edit_version+1
 WHERE definition_id=p_id AND edit_version=p_version AND source_json::jsonb=p_source::jsonb;
 IF NOT FOUND THEN RAISE EXCEPTION 'Definition version conflict' USING ERRCODE='40001'; END IF;
END $$;

CREATE TABLE game_instance.rules_session (
 session_id uuid PRIMARY KEY,
 definition_id bigint NOT NULL REFERENCES game_engine.rules_definition(definition_id),
 owner_user_id bigint NOT NULL REFERENCES game_instance.app_user(user_id),
 visibility text NOT NULL CHECK(visibility IN ('private','public')),
 captured_definition text NOT NULL, fingerprint text NOT NULL, compiler_version text NOT NULL, runtime_version text NOT NULL, modules_json jsonb NOT NULL,
 initial_checkpoint text NOT NULL, checkpoint_json text NOT NULL, state_version bigint NOT NULL DEFAULT 0 CHECK(state_version>=0),
 status text NOT NULL CHECK(status IN ('SETUP','RUNNING','FINISHED')), paused boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(COALESCE((checkpoint_json::jsonb->>'version')::bigint,-1)=state_version),
 CHECK(COALESCE(checkpoint_json::jsonb->>'definitionFingerprint','')=fingerprint),
 CHECK(COALESCE(checkpoint_json::jsonb->>'runtimeVersion','')=runtime_version),
 CHECK(COALESCE(checkpoint_json::jsonb->>'sessionId','')=session_id::text),
 CHECK(COALESCE(checkpoint_json::jsonb->'control'->>'status','')=status)
);
CREATE TABLE game_instance.rules_seat (
 session_id uuid NOT NULL REFERENCES game_instance.rules_session ON DELETE CASCADE,
 player_id text NOT NULL, seat_number integer NOT NULL, user_id bigint NOT NULL REFERENCES game_instance.app_user,
 PRIMARY KEY(session_id,player_id),UNIQUE(session_id,seat_number)
);
CREATE TABLE game_instance.rules_viewer (
 session_id uuid NOT NULL REFERENCES game_instance.rules_session ON DELETE CASCADE,
 user_id bigint NOT NULL REFERENCES game_instance.app_user,PRIMARY KEY(session_id,user_id)
);
CREATE TABLE game_instance.rules_transition (
 session_id uuid NOT NULL REFERENCES game_instance.rules_session ON DELETE CASCADE,
 state_version bigint NOT NULL, commit_json text NOT NULL, paused boolean NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(session_id,state_version),
 CHECK((commit_json::jsonb->>'version')::bigint=state_version), CHECK(commit_json::jsonb->>'sessionId'=session_id::text)
);
CREATE TABLE game_instance.rules_command (
 session_id uuid NOT NULL REFERENCES game_instance.rules_session ON DELETE CASCADE,
 user_id bigint NOT NULL REFERENCES game_instance.app_user, actor_key text NOT NULL, command_id text NOT NULL,
 request_hash text NOT NULL, response_json text NOT NULL, http_status integer NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(session_id,user_id,actor_key,command_id)
);
CREATE FUNCTION game_instance.trg_rules_capture_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.definition_id,NEW.owner_user_id,NEW.visibility,NEW.captured_definition,NEW.fingerprint,NEW.compiler_version,NEW.runtime_version,NEW.modules_json,NEW.initial_checkpoint)
 IS DISTINCT FROM (OLD.definition_id,OLD.owner_user_id,OLD.visibility,OLD.captured_definition,OLD.fingerprint,OLD.compiler_version,OLD.runtime_version,OLD.modules_json,OLD.initial_checkpoint)
 THEN RAISE EXCEPTION 'Captured rules and membership context are immutable' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER rules_capture_immutable BEFORE UPDATE ON game_instance.rules_session FOR EACH ROW EXECUTE FUNCTION game_instance.trg_rules_capture_immutable();
CREATE FUNCTION game_instance.trg_rules_record_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Runtime records are append only' USING ERRCODE='55000'; END $$;
CREATE TRIGGER rules_transition_immutable BEFORE UPDATE ON game_instance.rules_transition FOR EACH ROW EXECUTE FUNCTION game_instance.trg_rules_record_immutable();
CREATE TRIGGER rules_command_immutable BEFORE UPDATE ON game_instance.rules_command FOR EACH ROW EXECUTE FUNCTION game_instance.trg_rules_record_immutable();
CREATE TRIGGER rules_seat_immutable BEFORE UPDATE ON game_instance.rules_seat FOR EACH ROW EXECUTE FUNCTION game_instance.trg_rules_record_immutable();

CREATE FUNCTION game_instance.fn_rules_session_get(p_id uuid) RETURNS SETOF game_instance.rules_session LANGUAGE sql STABLE AS $$ SELECT * FROM game_instance.rules_session WHERE session_id=p_id $$;
CREATE FUNCTION game_instance.fn_rules_session_lock(p_id uuid) RETURNS SETOF game_instance.rules_session LANGUAGE sql VOLATILE AS $$ SELECT * FROM game_instance.rules_session WHERE session_id=p_id FOR UPDATE $$;
CREATE FUNCTION game_instance.fn_rules_seats(p_id uuid) RETURNS SETOF game_instance.rules_seat LANGUAGE sql STABLE AS $$ SELECT * FROM game_instance.rules_seat WHERE session_id=p_id ORDER BY seat_number $$;
CREATE FUNCTION game_instance.fn_rules_can_view(p_id uuid,p_user bigint,p_admin boolean) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM game_instance.rules_session s WHERE session_id=p_id AND (p_admin OR visibility='public' OR owner_user_id=p_user
  OR EXISTS(SELECT 1 FROM game_instance.rules_seat WHERE session_id=p_id AND user_id=p_user)
  OR EXISTS(SELECT 1 FROM game_instance.rules_viewer WHERE session_id=p_id AND user_id=p_user)))
$$;
CREATE FUNCTION game_instance.fn_rules_command_get(p_id uuid,p_user bigint,p_actor text,p_command text) RETURNS SETOF game_instance.rules_command LANGUAGE sql STABLE AS $$
 SELECT * FROM game_instance.rules_command WHERE session_id=p_id AND user_id=p_user AND actor_key=p_actor AND command_id=p_command
$$;
CREATE FUNCTION game_instance.fn_rules_transitions(p_id uuid,p_through bigint) RETURNS SETOF game_instance.rules_transition LANGUAGE sql STABLE AS $$
 SELECT * FROM game_instance.rules_transition WHERE session_id=p_id AND state_version<=p_through ORDER BY state_version
$$;
CREATE PROCEDURE game_instance.sp_rules_session_create(p_id uuid,p_definition bigint,p_owner bigint,p_visibility text,p_initial text,p_seats text,p_viewers text) LANGUAGE plpgsql AS $$
DECLARE d game_engine.rules_definition%ROWTYPE; member jsonb; ordinal integer:=0;
BEGIN
 SELECT * INTO STRICT d FROM game_engine.rules_definition WHERE definition_id=p_definition AND lifecycle='published' FOR SHARE;
 IF (p_initial::jsonb->>'version')::bigint IS DISTINCT FROM 0 OR p_initial::jsonb->'control'->>'status' IS DISTINCT FROM 'SETUP' THEN RAISE EXCEPTION 'Initial checkpoint required'; END IF;
 INSERT INTO game_instance.rules_session(session_id,definition_id,owner_user_id,visibility,captured_definition,fingerprint,compiler_version,runtime_version,modules_json,initial_checkpoint,checkpoint_json,status)
 VALUES(p_id,p_definition,p_owner,p_visibility,d.source_json,d.fingerprint,d.compiler_version,d.runtime_version,d.modules_json,p_initial,p_initial,'SETUP');
 FOR member IN SELECT value FROM jsonb_array_elements(p_seats::jsonb) LOOP
  ordinal:=ordinal+1;
  IF NOT EXISTS(SELECT 1 FROM game_instance.app_user WHERE user_id=(member->>'userId')::bigint AND active) THEN RAISE EXCEPTION 'Active seat user required'; END IF;
  INSERT INTO game_instance.rules_seat VALUES(p_id,member->>'playerId',ordinal,(member->>'userId')::bigint);
 END LOOP;
 IF (SELECT jsonb_agg(player_id ORDER BY seat_number) FROM game_instance.rules_seat WHERE session_id=p_id) IS DISTINCT FROM p_initial::jsonb->'players' THEN RAISE EXCEPTION 'Seat/checkpoint mismatch'; END IF;
 FOR member IN SELECT value FROM jsonb_array_elements(p_viewers::jsonb) LOOP
  INSERT INTO game_instance.rules_viewer VALUES(p_id,(member#>>'{}')::bigint);
 END LOOP;
END $$;
CREATE PROCEDURE game_instance.sp_rules_command_save(p_id uuid,p_expected bigint,p_checkpoint text,p_commit text,p_paused boolean,
 p_user bigint,p_actor text,p_command text,p_hash text,p_response text,p_http integer) LANGUAGE plpgsql AS $$
DECLARE previous bigint; original jsonb; expected_state jsonb; removed text[];
BEGIN
 SELECT state_version,checkpoint_json::jsonb INTO STRICT previous,original FROM game_instance.rules_session WHERE session_id=p_id FOR UPDATE;
 IF previous<>p_expected THEN RAISE EXCEPTION 'Session version conflict' USING ERRCODE='40001'; END IF;
 IF p_commit IS NOT NULL THEN
  IF (p_commit::jsonb->>'beforeVersion')::bigint IS DISTINCT FROM previous OR (p_commit::jsonb->>'version')::bigint IS DISTINCT FROM previous+1
     OR (p_checkpoint::jsonb->>'version')::bigint IS DISTINCT FROM previous+1
     OR p_commit::jsonb->>'definitionFingerprint' IS DISTINCT FROM original->>'definitionFingerprint'
     OR p_commit::jsonb->>'runtimeVersion' IS DISTINCT FROM original->>'runtimeVersion'
     OR p_commit::jsonb->>'sessionId' IS DISTINCT FROM p_id::text THEN RAISE EXCEPTION 'Invalid transition identity/version'; END IF;
  SELECT COALESCE(array_agg(value),'{}'::text[]) INTO removed FROM jsonb_array_elements_text(p_commit::jsonb->'removedEntities');
  expected_state:=original||jsonb_build_object('version',previous+1,'globals',(original->'globals')||(p_commit::jsonb->'globals'),
      'entities',((original->'entities')-removed)||(p_commit::jsonb->'entities'),'control',p_commit::jsonb->'control','events',(original->'events')||(p_commit::jsonb->'events'));
  IF expected_state IS DISTINCT FROM p_checkpoint::jsonb THEN RAISE EXCEPTION 'Checkpoint and commit differ'; END IF;
  IF EXISTS(SELECT 1 FROM jsonb_array_elements(p_commit::jsonb->'events') WITH ORDINALITY e(value,n)
            WHERE (value->>'sequence')::bigint IS DISTINCT FROM jsonb_array_length(original->'events')+n) THEN RAISE EXCEPTION 'Event sequence mismatch'; END IF;
  UPDATE game_instance.rules_session SET checkpoint_json=p_checkpoint,state_version=previous+1,status=p_checkpoint::jsonb->'control'->>'status',paused=p_paused,updated_at=now() WHERE session_id=p_id;
  INSERT INTO game_instance.rules_transition(session_id,state_version,commit_json,paused) VALUES(p_id,previous+1,p_commit,p_paused);
 END IF;
 INSERT INTO game_instance.rules_command(session_id,user_id,actor_key,command_id,request_hash,response_json,http_status)
 VALUES(p_id,p_user,p_actor,p_command,p_hash,p_response,p_http);
END $$;
CREATE PROCEDURE game_instance.sp_rules_session_delete(p_id uuid) LANGUAGE plpgsql AS $$ BEGIN DELETE FROM game_instance.rules_session WHERE session_id=p_id; END $$;
