-- Personal reusable fleets for interpreted rules. Selections and source snapshots are immutable;
-- only the display name can be changed, using an optimistic edit version.
CREATE TABLE game_instance.rules_fleet_preset (
 preset_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 owner_user_id bigint NOT NULL REFERENCES game_instance.app_user,
 definition_id bigint NOT NULL REFERENCES game_engine.rules_definition,
 source_seat integer NOT NULL CHECK (source_seat >= 0),
 preset_name text NOT NULL CHECK (preset_name = btrim(preset_name) AND char_length(preset_name) BETWEEN 1 AND 100),
 edit_version bigint NOT NULL DEFAULT 0 CHECK (edit_version >= 0),
 policy_code text NOT NULL CHECK (char_length(policy_code) > 0),
 units_json jsonb NOT NULL CHECK (jsonb_typeof(units_json) = 'array'),
 snapshot_json jsonb NOT NULL CHECK ((jsonb_typeof(snapshot_json) = 'object'
   AND snapshot_json ?& ARRAY['definition','revision','fingerprint','points','units']
   AND snapshot_json->>'fingerprint' ~ '^[0-9a-f]{64}$'
   AND jsonb_typeof(snapshot_json->'units') = 'array'
   AND (snapshot_json->>'points')::bigint >= 0) IS TRUE),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX rules_fleet_preset_name ON game_instance.rules_fleet_preset (owner_user_id, lower(preset_name));

CREATE FUNCTION game_instance.trg_rules_fleet_preset_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.preset_id,NEW.owner_user_id,NEW.definition_id,NEW.source_seat,NEW.policy_code,NEW.units_json,NEW.snapshot_json,NEW.created_at)
 IS DISTINCT FROM (OLD.preset_id,OLD.owner_user_id,OLD.definition_id,OLD.source_seat,OLD.policy_code,OLD.units_json,OLD.snapshot_json,OLD.created_at)
 OR NEW.edit_version <> OLD.edit_version + 1
 THEN RAISE EXCEPTION 'Preset selection and source revision are immutable' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER rules_fleet_preset_immutable BEFORE UPDATE ON game_instance.rules_fleet_preset
 FOR EACH ROW EXECUTE FUNCTION game_instance.trg_rules_fleet_preset_immutable();

CREATE FUNCTION game_instance.fn_rules_fleet_presets(p_owner bigint) RETURNS SETOF game_instance.rules_fleet_preset
 LANGUAGE sql STABLE AS $$ SELECT * FROM game_instance.rules_fleet_preset WHERE owner_user_id=p_owner ORDER BY updated_at DESC,preset_id DESC $$;
CREATE FUNCTION game_instance.fn_rules_fleet_preset_get(p_owner bigint,p_id bigint) RETURNS SETOF game_instance.rules_fleet_preset
 LANGUAGE sql STABLE AS $$ SELECT * FROM game_instance.rules_fleet_preset WHERE owner_user_id=p_owner AND preset_id=p_id $$;

CREATE PROCEDURE game_instance.sp_rules_fleet_preset_create(p_owner bigint,p_definition bigint,p_seat integer,p_name text,
 p_policy text,p_units text,p_snapshot text,INOUT p_id bigint) LANGUAGE plpgsql AS $$
DECLARE d game_engine.rules_definition%ROWTYPE; codes jsonb; total numeric;
BEGIN
 SELECT * INTO STRICT d FROM game_engine.rules_definition WHERE definition_id=p_definition AND lifecycle='published' FOR SHARE;
 IF p_snapshot::jsonb->>'definition' IS DISTINCT FROM d.definition_key
 OR p_snapshot::jsonb->>'revision' IS DISTINCT FROM d.revision
 OR p_snapshot::jsonb->>'fingerprint' IS DISTINCT FROM d.fingerprint
 THEN RAISE EXCEPTION 'Preset source identity mismatch'; END IF;
 SELECT COALESCE(jsonb_agg(value->>'code' ORDER BY n),'[]'::jsonb), COALESCE(sum((value->>'points')::numeric),0)
 INTO codes,total FROM jsonb_array_elements(p_snapshot::jsonb->'units') WITH ORDINALITY unit(value,n);
 IF codes IS DISTINCT FROM p_units::jsonb OR total IS DISTINCT FROM (p_snapshot::jsonb->>'points')::numeric
 THEN RAISE EXCEPTION 'Preset selection and cost snapshot differ'; END IF;
 INSERT INTO game_instance.rules_fleet_preset(owner_user_id,definition_id,source_seat,preset_name,policy_code,units_json,snapshot_json)
 VALUES(p_owner,p_definition,p_seat,p_name,p_policy,p_units::jsonb,p_snapshot::jsonb) RETURNING preset_id INTO p_id;
END $$;

CREATE PROCEDURE game_instance.sp_rules_fleet_preset_rename(p_owner bigint,p_id bigint,p_expected bigint,p_name text) LANGUAGE plpgsql AS $$
BEGIN
 UPDATE game_instance.rules_fleet_preset SET preset_name=p_name,edit_version=edit_version+1,updated_at=now()
 WHERE owner_user_id=p_owner AND preset_id=p_id AND edit_version=p_expected;
 IF NOT FOUND THEN
  IF EXISTS(SELECT 1 FROM game_instance.rules_fleet_preset WHERE owner_user_id=p_owner AND preset_id=p_id)
  THEN RAISE EXCEPTION 'Preset version conflict' USING ERRCODE='40001'; END IF;
  RAISE EXCEPTION 'Preset not found' USING ERRCODE='P0002';
 END IF;
END $$;
CREATE PROCEDURE game_instance.sp_rules_fleet_preset_delete(p_owner bigint,p_id bigint,p_expected bigint) LANGUAGE plpgsql AS $$
BEGIN
 DELETE FROM game_instance.rules_fleet_preset WHERE owner_user_id=p_owner AND preset_id=p_id AND edit_version=p_expected;
 IF NOT FOUND THEN
  IF EXISTS(SELECT 1 FROM game_instance.rules_fleet_preset WHERE owner_user_id=p_owner AND preset_id=p_id)
  THEN RAISE EXCEPTION 'Preset version conflict' USING ERRCODE='40001'; END IF;
  RAISE EXCEPTION 'Preset not found' USING ERRCODE='P0002';
 END IF;
END $$;
