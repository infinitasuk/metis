-- Retain authored documents alongside compiled resolved rules. Existing captures stay byte-for-byte intact.
ALTER TABLE game_engine.rules_definition ADD COLUMN resolved_json text, ADD COLUMN resolution_json jsonb;
ALTER TABLE game_engine.rules_definition ADD CONSTRAINT rules_resolved_pair CHECK ((resolved_json IS NULL)=(resolution_json IS NULL)),
 ADD CONSTRAINT rules_resolved_identity CHECK (resolved_json IS NULL OR
   (jsonb_typeof(resolved_json::jsonb)='object' AND (resolved_json::jsonb->>'id' IS NOT DISTINCT FROM definition_key) AND (resolved_json::jsonb->>'revision' IS NOT DISTINCT FROM revision))),
 ADD CONSTRAINT rules_mode_publication CHECK (lifecycle<>'published' OR NOT (source_json::jsonb ? 'modeVersion') OR resolved_json IS NOT NULL);
CREATE TABLE game_engine.rules_definition_dependency (
 definition_id bigint NOT NULL REFERENCES game_engine.rules_definition ON DELETE CASCADE,
 base_definition_id bigint NOT NULL REFERENCES game_engine.rules_definition,
 fingerprint text NOT NULL, PRIMARY KEY(definition_id,base_definition_id),CHECK(definition_id<>base_definition_id)
);
CREATE TRIGGER rules_dependency_immutable BEFORE UPDATE ON game_engine.rules_definition_dependency
 FOR EACH ROW EXECUTE FUNCTION game_instance.trg_rules_record_immutable();
CREATE FUNCTION game_engine.fn_rules_definition_published(p_key text,p_revision text) RETURNS SETOF game_engine.rules_definition
 LANGUAGE sql STABLE AS $$ SELECT * FROM game_engine.rules_definition WHERE definition_key=p_key AND revision=p_revision AND lifecycle='published' $$;
CREATE PROCEDURE game_engine.sp_rules_definition_publish_resolved(p_id bigint,p_version bigint,p_source text,p_resolved text,
 p_fingerprint text,p_compiler text,p_runtime text,p_modules text,p_resolution text) LANGUAGE plpgsql AS $$
DECLARE layer jsonb; base game_engine.rules_definition%ROWTYPE; current_definition game_engine.rules_definition%ROWTYPE;
BEGIN
 SELECT * INTO STRICT current_definition FROM game_engine.rules_definition WHERE definition_id=p_id FOR UPDATE;
 IF current_definition.lifecycle<>'draft' OR current_definition.edit_version<>p_version OR current_definition.source_json::jsonb IS DISTINCT FROM p_source::jsonb
 THEN RAISE EXCEPTION 'Definition version conflict' USING ERRCODE='40001'; END IF;
 IF p_resolution::jsonb->>'resolverVersion' IS DISTINCT FROM 'rules-resolver-1'
    OR jsonb_typeof(p_resolution::jsonb->'layers') IS DISTINCT FROM 'array'
    OR jsonb_array_length(p_resolution::jsonb->'layers') NOT BETWEEN 1 AND 16
    OR p_resolution::jsonb->'layers'->(-1)->>'fingerprint' IS DISTINCT FROM p_fingerprint
    OR p_resolution::jsonb->'layers'->(-1)->'reference'->>'id' IS DISTINCT FROM current_definition.definition_key
    OR p_resolution::jsonb->'layers'->(-1)->'reference'->>'revision' IS DISTINCT FROM current_definition.revision
 THEN RAISE EXCEPTION 'Invalid resolution metadata'; END IF;
 FOR layer IN SELECT value FROM jsonb_array_elements((p_resolution::jsonb->'layers')- (jsonb_array_length(p_resolution::jsonb->'layers')-1)) LOOP
  SELECT * INTO STRICT base FROM game_engine.rules_definition WHERE definition_key=layer->'reference'->>'id'
   AND revision=layer->'reference'->>'revision' AND lifecycle='published' FOR SHARE;
  IF base.fingerprint IS DISTINCT FROM layer->>'fingerprint' THEN RAISE EXCEPTION 'Base fingerprint mismatch'; END IF;
  INSERT INTO game_engine.rules_definition_dependency VALUES(p_id,base.definition_id,base.fingerprint);
 END LOOP;
 UPDATE game_engine.rules_definition SET source_json=p_source,resolved_json=p_resolved,resolution_json=p_resolution::jsonb,
  fingerprint=p_fingerprint,compiler_version=p_compiler,runtime_version=p_runtime,modules_json=p_modules::jsonb,
  lifecycle='published',published_at=now(),edit_version=edit_version+1 WHERE definition_id=p_id;
END $$;

ALTER TABLE game_instance.rules_session ADD COLUMN captured_resolution jsonb;
CREATE OR REPLACE FUNCTION game_instance.trg_rules_capture_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.definition_id,NEW.owner_user_id,NEW.visibility,NEW.captured_definition,NEW.fingerprint,NEW.compiler_version,NEW.runtime_version,NEW.modules_json,NEW.initial_checkpoint,NEW.captured_resolution)
 IS DISTINCT FROM (OLD.definition_id,OLD.owner_user_id,OLD.visibility,OLD.captured_definition,OLD.fingerprint,OLD.compiler_version,OLD.runtime_version,OLD.modules_json,OLD.initial_checkpoint,OLD.captured_resolution)
 THEN RAISE EXCEPTION 'Captured rules and membership context are immutable' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END $$;
CREATE OR REPLACE PROCEDURE game_instance.sp_rules_session_create(p_id uuid,p_definition bigint,p_owner bigint,p_visibility text,p_initial text,p_seats text,p_viewers text) LANGUAGE plpgsql AS $$
DECLARE d game_engine.rules_definition%ROWTYPE; member jsonb; ordinal integer:=0;
BEGIN
 SELECT * INTO STRICT d FROM game_engine.rules_definition WHERE definition_id=p_definition AND lifecycle='published' FOR SHARE;
 IF (p_initial::jsonb->>'version')::bigint IS DISTINCT FROM 0 OR p_initial::jsonb->'control'->>'status' IS DISTINCT FROM 'SETUP' THEN RAISE EXCEPTION 'Initial checkpoint required'; END IF;
 INSERT INTO game_instance.rules_session(session_id,definition_id,owner_user_id,visibility,captured_definition,fingerprint,compiler_version,runtime_version,modules_json,initial_checkpoint,checkpoint_json,status,captured_resolution)
 VALUES(p_id,p_definition,p_owner,p_visibility,COALESCE(d.resolved_json,d.source_json),d.fingerprint,d.compiler_version,d.runtime_version,d.modules_json,p_initial,p_initial,'SETUP',d.resolution_json);
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
