ALTER TABLE game_engine.game_version ADD COLUMN turn_structure jsonb;

-- Compatibility configuration for the existing catalogue. These identifiers and
-- classifications are data; the sequencer has no knowledge of their game meaning.
UPDATE game_engine.game_version SET turn_structure = '{
 "deploymentRequired":true,
 "phases":[
  {"code":"command","label":"Command","mode":"MANUAL","groupSize":1,"steps":[]},
  {"code":"ship","label":"Ship activations","mode":"ALTERNATING_UNITS","classifications":["Ship"],"groupSize":1,
   "steps":[
    {"code":"attacks","label":"Attacks","actions":["attack_unit"],"minimum":0,"maximum":2,"distinctParameter":"arc"},
    {"code":"movement","label":"Maneuver","actions":["move_unit"],"minimum":1,"maximum":1,"editableMovement":true}]},
  {"code":"squadron","label":"Squadron activations","mode":"ALTERNATING_UNITS","classifications":["Squadron"],"groupSize":2,
   "steps":[{"code":"action","label":"Move or attack","actions":["move_unit","attack_unit"],"minimum":0,"maximum":1,"editableMovement":true}]},
  {"code":"status","label":"Status","mode":"MANUAL","groupSize":1,"steps":[],"refreshResourcesAtEnd":true}
 ]
}'::jsonb WHERE profile_mapping ? 'health';

CREATE FUNCTION game_engine.trg_turn_structure_defaults() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.turn_structure IS NULL THEN
  SELECT turn_structure INTO NEW.turn_structure FROM game_engine.game_version
  WHERE game_id = NEW.game_id AND lifecycle_status = 'published'
  ORDER BY published_at DESC NULLS LAST, game_version_id DESC LIMIT 1;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER turn_structure_defaults_trg BEFORE INSERT ON game_engine.game_version
FOR EACH ROW EXECUTE FUNCTION game_engine.trg_turn_structure_defaults();

CREATE FUNCTION game_engine.trg_turn_structure_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.lifecycle_status <> 'draft' AND NEW.turn_structure IS DISTINCT FROM OLD.turn_structure THEN
  RAISE EXCEPTION 'Turn structures can only be edited on a draft definition';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER turn_structure_immutable_trg BEFORE UPDATE ON game_engine.game_version
FOR EACH ROW EXECUTE FUNCTION game_engine.trg_turn_structure_immutable();

CREATE FUNCTION game_engine.fn_definition_turn_structure_get(p_id bigint) RETURNS jsonb LANGUAGE sql STABLE AS $$
 SELECT turn_structure FROM game_engine.game_version WHERE game_version_id = p_id
$$;
CREATE PROCEDURE game_engine.sp_definition_turn_structure_update(p_id bigint, p_configuration text)
LANGUAGE plpgsql AS $$
BEGIN
 IF jsonb_typeof(p_configuration::jsonb) IS DISTINCT FROM 'object' THEN RAISE EXCEPTION 'Turn structure must be an object'; END IF;
 UPDATE game_engine.game_version SET turn_structure = p_configuration::jsonb WHERE game_version_id = p_id AND lifecycle_status = 'draft';
 IF NOT FOUND THEN RAISE EXCEPTION 'Turn structure requires an existing draft definition'; END IF;
 INSERT INTO game_engine.definition_audit(game_version_id, action_code, entity_type, entity_id, details_json)
 VALUES (p_id, 'turn_structure_updated', 'game_version', p_id, p_configuration::jsonb);
END $$;

CREATE TABLE game_instance.session_definition_snapshot (
 session_id bigint PRIMARY KEY REFERENCES game_instance.game_session(session_id) ON DELETE CASCADE,
 snapshot_schema_version integer NOT NULL,
 rules_version text NOT NULL,
 definition_json text NOT NULL CHECK (jsonb_typeof(definition_json::jsonb) = 'object'),
 checksum text NOT NULL,
 captured_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION game_instance.trg_definition_snapshot_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Captured session definitions cannot be updated; restart as a new battle'; END $$;
CREATE TRIGGER session_definition_snapshot_immutable_trg BEFORE UPDATE ON game_instance.session_definition_snapshot
FOR EACH ROW EXECUTE FUNCTION game_instance.trg_definition_snapshot_immutable();

CREATE FUNCTION game_instance.fn_session_definition_snapshot_get(p_id bigint)
RETURNS SETOF game_instance.session_definition_snapshot LANGUAGE sql STABLE AS $$
 SELECT * FROM game_instance.session_definition_snapshot WHERE session_id = p_id
$$;
CREATE PROCEDURE game_instance.sp_session_definition_snapshot_create(p_id bigint, p_schema integer, p_rules text, p_json text, p_checksum text)
LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM game_instance.game_session WHERE session_id = p_id
   AND game_version_id = (p_json::jsonb->>'definitionVersionId')::bigint AND status = 'setup') THEN
  RAISE EXCEPTION 'Capture requires a setup session with the matching definition revision';
 END IF;
 INSERT INTO game_instance.session_definition_snapshot(session_id, snapshot_schema_version, rules_version, definition_json, checksum)
 VALUES(p_id, p_schema, p_rules, p_json, p_checksum);
END $$;
