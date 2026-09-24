-- Upgrade databases initialized by docker/init/sql/001..031. Flyway owns all
-- subsequent migrations. This migration changes projection metadata, not battles.
ALTER TABLE game_engine.game_version
    ADD COLUMN profile_mapping jsonb NOT NULL DEFAULT '{}'::jsonb
    CHECK (jsonb_typeof(profile_mapping) = 'object');

-- Preserve existing catalogue conventions as DATA on each legacy revision.
-- New games default to an empty mapping and must supply their own bindings.
-- Binding precedence is explicit: aliases are tried in their configured order.
UPDATE game_engine.game_version SET profile_mapping = '{
  "health": {"sources":["Hull","Hull Value"],"required":true},
  "movement": {"sources":["Max Speed","Speed"],"default":0},
  "attackRange": {"default":300},
  "commandCapacity": {"sources":["Command"],"default":0},
  "repairCapacity": {"sources":["Engineering"],"default":0},
  "activationCapacity": {"sources":["Squadron"],"default":0},
  "defenseTokens": {"sources":["Defense Tokens"],"default":[]},
  "antiUnitDice": {"sources":["Anti-Squadron","Anti-Squadron Value"],"default":[]},
  "weapons": {
    "front":{"sources":["Front Firing Arc","Battery"],"default":[]},
    "left":{"sources":["Left Firing Arc"],"default":[]},
    "right":{"sources":["Right Firing Arc"],"default":[]},
    "rear":{"sources":["Rear Firing Arc"],"default":[]}
  },
  "defenseZones": {
    "front":{"sources":["Front Shield Value"],"default":0},
    "left":{"sources":["Left Shield Value"],"default":0},
    "right":{"sources":["Right Shield Value"],"default":0},
    "rear":{"sources":["Rear Shield Value"],"default":0}
  },
  "baseRadius":50,
  "classificationBaseRadii":{"Squadron":25}
}'::jsonb;

CREATE FUNCTION game_engine.trg_profile_mapping_defaults()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.profile_mapping = '{}'::jsonb THEN
        SELECT COALESCE((
            SELECT gv.profile_mapping FROM game_engine.game_version gv
            WHERE gv.game_id = NEW.game_id AND gv.lifecycle_status = 'published'
            ORDER BY gv.published_at DESC NULLS LAST, gv.game_version_id DESC LIMIT 1
        ), '{}'::jsonb) INTO NEW.profile_mapping;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER profile_mapping_defaults_trg BEFORE INSERT ON game_engine.game_version
    FOR EACH ROW EXECUTE FUNCTION game_engine.trg_profile_mapping_defaults();

CREATE FUNCTION game_engine.trg_profile_mapping_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.lifecycle_status <> 'draft' AND NEW.profile_mapping IS DISTINCT FROM OLD.profile_mapping THEN
        RAISE EXCEPTION 'Profile mappings can only be edited on a draft definition';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER profile_mapping_immutable_trg BEFORE UPDATE ON game_engine.game_version
    FOR EACH ROW EXECUTE FUNCTION game_engine.trg_profile_mapping_immutable();

-- Pure readers: neither function knows a game, unit class, nor stat name.
CREATE FUNCTION game_engine.fn_profile_value(p_values jsonb, p_binding jsonb, p_field text)
RETURNS jsonb LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE v_name text; v_value jsonb;
BEGIN
    FOR v_name IN SELECT jsonb_array_elements_text(COALESCE(p_binding->'sources', '[]'::jsonb)) LOOP
        v_value := p_values->v_name;
        IF v_value IS NOT NULL AND v_value <> 'null'::jsonb AND v_value <> '""'::jsonb THEN
            RETURN v_value;
        END IF;
    END LOOP;
    v_value := p_binding->'default';
    IF (v_value IS NULL OR v_value = 'null'::jsonb) AND COALESCE((p_binding->>'required')::boolean, false) THEN
        RAISE EXCEPTION 'Required profile field % has no mapped source value', p_field;
    END IF;
    RETURN NULLIF(v_value, 'null'::jsonb);
END
$$;

CREATE FUNCTION game_engine.fn_profile_number(p_values jsonb, p_binding jsonb, p_field text)
RETURNS numeric LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE v_value jsonb; v_number numeric;
BEGIN
    v_value := game_engine.fn_profile_value(p_values, p_binding, p_field);
    IF v_value IS NULL THEN RETURN NULL; END IF;
    IF jsonb_typeof(v_value) NOT IN ('number','string') THEN
        RAISE EXCEPTION 'Profile field % must be numeric', p_field;
    END IF;
    BEGIN
        v_number := (v_value #>> '{}')::numeric;
    EXCEPTION WHEN invalid_text_representation THEN
        RAISE EXCEPTION 'Profile field % must be numeric', p_field;
    END;
    IF v_number < 0 OR v_number::text IN ('NaN','Infinity','-Infinity') THEN
        RAISE EXCEPTION 'Profile field % must be finite and nonnegative', p_field;
    END IF;
    RETURN v_number;
END
$$;

-- Compatibility projection to the current typed UnitDefinition contract.
-- Source names, zone names and numeric defaults come from revision metadata.
CREATE OR REPLACE FUNCTION game_engine.fn_engine_definition_units(p_definition_id bigint)
RETURNS jsonb LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_mapping jsonb;
    v_row record;
    v_values jsonb;
    v_health numeric;
    v_tokens jsonb;
    v_token_counts jsonb;
    v_weapons jsonb;
    v_zones jsonb;
    v_dice integer;
    v_profiles jsonb;
    v_result jsonb := '[]'::jsonb;
BEGIN
    SELECT profile_mapping INTO v_mapping FROM game_engine.game_version WHERE game_version_id = p_definition_id;
    FOR v_row IN
        SELECT uv.unit_variant_id, uv.unit_variant_name, uc.unit_classification_name, f.faction_name
        FROM game_engine.unit_variant uv
        JOIN game_engine.unit_type ut ON ut.unit_type_id = uv.unit_type_id
        JOIN game_engine.unit_classification uc ON uc.unit_classification_id = ut.unit_classification_id
        JOIN game_engine.faction f ON f.faction_id = uc.faction_id
        WHERE f.game_version_id = p_definition_id ORDER BY uv.unit_variant_id
    LOOP
        SELECT COALESCE(jsonb_object_agg(ct.characteristic_type_name, c.characteristic_json->'value'), '{}'::jsonb)
        INTO v_values
        FROM game_engine.unit_variant_characteristic c
        JOIN game_engine.characteristic_type ct ON ct.characteristic_type_id = c.characteristic_type_id
        WHERE c.unit_variant_id = v_row.unit_variant_id;

        v_health := game_engine.fn_profile_number(v_values, v_mapping->'health', 'health');
        IF v_health IS NULL OR v_health < 1 OR v_health <> trunc(v_health) THEN
            RAISE EXCEPTION 'Unit % requires a positive integral health value; configure its definition mapping', v_row.unit_variant_id;
        END IF;

        v_tokens := COALESCE(game_engine.fn_profile_value(v_values, v_mapping->'defenseTokens', 'defenseTokens'), '[]'::jsonb);
        SELECT COALESCE(jsonb_object_agg(name, count), '{}'::jsonb) INTO v_token_counts
        FROM (SELECT lower(token->>'name') AS name, SUM(COALESCE((token->>'count')::integer, 1)) AS count
              FROM jsonb_array_elements(v_tokens) token GROUP BY lower(token->>'name')) t;

        SELECT COALESCE(jsonb_object_agg(key, COALESCE(game_engine.fn_profile_value(v_values, value, key), '[]'::jsonb)), '{}'::jsonb)
        INTO v_weapons FROM jsonb_each(COALESCE(v_mapping->'weapons', '{}'::jsonb));
        SELECT COALESCE(jsonb_object_agg(key, COALESCE(game_engine.fn_profile_number(v_values, value, key), 0)), '{}'::jsonb)
        INTO v_zones FROM jsonb_each(COALESCE(v_mapping->'defenseZones', '{}'::jsonb));
        SELECT GREATEST(1, COALESCE(SUM((die->>'count')::integer), 0)) INTO v_dice
        FROM jsonb_array_elements(COALESCE(v_weapons->'front', '[]'::jsonb)) die;
        SELECT COALESCE(jsonb_object_agg(speed::text, maximum_yaw_clicks ORDER BY speed), '{}'::jsonb)
        INTO v_profiles FROM game_engine.unit_variant_maneuver_profile WHERE unit_variant_id = v_row.unit_variant_id;

        v_result := v_result || jsonb_build_array(jsonb_build_object(
            'code', v_row.unit_variant_id::text,
            'displayName', v_row.unit_variant_name,
            'classification', v_row.unit_classification_name,
            'factionCode', v_row.faction_name,
            'maximumHullPoints', v_health,
            'maximumMovementDistance', COALESCE(game_engine.fn_profile_number(v_values, v_mapping->'movement', 'movement'), 0),
            'maximumAttackRange', COALESCE(game_engine.fn_profile_number(v_values, v_mapping->'attackRange', 'attackRange'), 0),
            'commandValue', COALESCE(game_engine.fn_profile_number(v_values, v_mapping->'commandCapacity', 'commandCapacity'), 0),
            'engineeringValue', COALESCE(game_engine.fn_profile_number(v_values, v_mapping->'repairCapacity', 'repairCapacity'), 0),
            'squadronValue', COALESCE(game_engine.fn_profile_number(v_values, v_mapping->'activationCapacity', 'activationCapacity'), 0),
            'attackDamage', COALESCE(game_engine.fn_profile_number(v_values, v_mapping->'attackDamage', 'attackDamage'), v_dice),
            'attackDice', v_dice,
            'maximumShieldPoints', (SELECT COALESCE(SUM(value::integer), 0) FROM jsonb_each_text(v_zones)),
            'shieldZones', v_zones,
            'batteryArcs', v_weapons,
            'antiSquadronDice', COALESCE(game_engine.fn_profile_value(v_values, v_mapping->'antiUnitDice', 'antiUnitDice'), '[]'::jsonb),
            'defenseTokenCount', (SELECT COALESCE(SUM(value::integer), 0) FROM jsonb_each_text(v_token_counts)),
            'defenseTokenCounts', v_token_counts,
            'defenseTokenTypes', (SELECT COALESCE(jsonb_agg(key ORDER BY key), '[]'::jsonb) FROM jsonb_each(v_token_counts)),
            'maneuverProfiles', v_profiles,
            'baseRadius', COALESCE((v_mapping->'classificationBaseRadii'->>v_row.unit_classification_name)::numeric,
                                   (v_mapping->>'baseRadius')::numeric, 1)
        ));
    END LOOP;
    RETURN v_result;
END
$$;

CREATE FUNCTION game_engine.fn_definition_profile_mapping_get(p_definition_id bigint)
RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT profile_mapping FROM game_engine.game_version WHERE game_version_id = p_definition_id
$$;

CREATE PROCEDURE game_engine.sp_definition_profile_mapping_update(p_definition_id bigint, p_mapping text)
LANGUAGE plpgsql AS $$
BEGIN
    IF jsonb_typeof(p_mapping::jsonb) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'Profile mapping must be a JSON object';
    END IF;
    UPDATE game_engine.game_version SET profile_mapping = p_mapping::jsonb
    WHERE game_version_id = p_definition_id AND lifecycle_status = 'draft';
    IF NOT FOUND THEN RAISE EXCEPTION 'Profile mapping requires an existing draft definition'; END IF;
    -- Reject mappings that cannot project the draft's current catalogue atomically.
    PERFORM game_engine.fn_engine_definition_units(p_definition_id);
    INSERT INTO game_engine.definition_audit(game_version_id, action_code, entity_type, entity_id, details_json)
    VALUES (p_definition_id, 'profile_mapping_updated', 'game_version', p_definition_id, p_mapping::jsonb);
END
$$;
