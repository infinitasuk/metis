-- Run with psql -v ON_ERROR_STOP=1 after migrations. Fixtures are rolled back.
BEGIN;
DO $test$
DECLARE
    v_game bigint; v_version bigint; v_faction bigint; v_class bigint;
    v_type bigint; v_unit bigint; v_characteristic bigint; v_clone bigint;
    v_profile jsonb; v_mapping jsonb; v_rejected boolean;
    v_name text;
BEGIN
    ASSERT game_engine.fn_profile_number('{"Hull":11,"Hull Value":4}',
        '{"sources":["Hull","Hull Value"],"required":true}', 'health') = 11;
    ASSERT game_engine.fn_profile_number('{"Hull Value":4}',
        '{"sources":["Hull","Hull Value"],"required":true}', 'health') = 4;
    ASSERT game_engine.fn_profile_number('{"Wounds":0}',
        '{"sources":["Wounds"],"default":8}', 'resource') = 0;
    ASSERT game_engine.fn_profile_number('{}', '{"default":0}', 'optional') = 0;
    v_rejected := false;
    BEGIN
        PERFORM game_engine.fn_profile_number('{}', '{"sources":["Missing"],"required":true}', 'health');
    EXCEPTION WHEN raise_exception THEN v_rejected := true;
    END;
    ASSERT v_rejected, 'Required health cannot silently become one';
    v_rejected := false;
    BEGIN
        PERFORM game_engine.fn_profile_number('{"Wounds":"bad"}', '{"sources":["Wounds"]}', 'health');
    EXCEPTION WHEN raise_exception THEN v_rejected := true;
    END;
    ASSERT v_rejected, 'Malformed data must fail';
    v_rejected := false;
    BEGIN
        PERFORM game_engine.fn_profile_number('{"Wounds":-2}', '{"sources":["Wounds"]}', 'health');
    EXCEPTION WHEN raise_exception THEN v_rejected := true;
    END;
    ASSERT v_rejected, 'Negative data must fail';

    -- Two independent game definitions use identical projection code, different bindings.
    FOREACH v_name IN ARRAY ARRAY['Wounds','Hull segments'] LOOP
        INSERT INTO game_engine.game(game_name, minimum_number_of_players, maximum_number_of_players,
                                     minimum_number_of_cpu, maximum_number_of_cpu)
        VALUES ('Projection fixture ' || v_name, 2, 2, 0, 2) RETURNING game_id INTO v_game;
        INSERT INTO game_engine.game_version(game_id, version_code, version_name, lifecycle_status)
        VALUES (v_game, 'fixture', 'Fixture', 'draft') RETURNING game_version_id, profile_mapping INTO v_version, v_mapping;
        ASSERT v_mapping = '{}'::jsonb, 'New games must not inherit unrelated defaults';
        INSERT INTO game_engine.faction(game_id, game_version_id, faction_name)
        VALUES (v_game, v_version, 'Fixture faction') RETURNING faction_id INTO v_faction;
        INSERT INTO game_engine.unit_classification(faction_id, unit_classification_name)
        VALUES (v_faction, 'Custom entity') RETURNING unit_classification_id INTO v_class;
        INSERT INTO game_engine.unit_type(unit_classification_id, unit_type_name)
        VALUES (v_class, 'Custom type') RETURNING unit_type_id INTO v_type;
        INSERT INTO game_engine.unit_variant(unit_type_id, unit_variant_name)
        VALUES (v_type, 'Custom unit') RETURNING unit_variant_id INTO v_unit;
        INSERT INTO game_engine.characteristic_type(characteristic_type_name) VALUES (v_name)
        ON CONFLICT (characteristic_type_name) DO UPDATE SET characteristic_type_name = EXCLUDED.characteristic_type_name
        RETURNING characteristic_type_id INTO v_characteristic;
        INSERT INTO game_engine.unit_variant_characteristic(unit_variant_id, characteristic_type_id, characteristic_json)
        VALUES (v_unit, v_characteristic, '{"value":7}');
        v_mapping := jsonb_build_object('health', jsonb_build_object('sources', jsonb_build_array(v_name), 'required', true));
        CALL game_engine.sp_definition_profile_mapping_update(v_version, v_mapping::text);
        v_profile := game_engine.fn_engine_definition_units(v_version)->0;
        ASSERT (v_profile->>'maximumHullPoints')::int = 7;
        ASSERT (v_profile->>'maximumAttackRange')::int = 0, 'No implicit legacy weapon range';
        ASSERT (v_profile->>'commandValue')::int = 0;
        ASSERT v_profile->'defenseTokenCounts' = '{}'::jsonb;
        ASSERT v_profile->'batteryArcs' = '{}'::jsonb;

        UPDATE game_engine.game_version SET lifecycle_status = 'published' WHERE game_version_id = v_version;
        v_rejected := false;
        BEGIN
            UPDATE game_engine.game_version SET profile_mapping = '{}' WHERE game_version_id = v_version;
        EXCEPTION WHEN raise_exception THEN v_rejected := true;
        END;
        ASSERT v_rejected, 'Published mapping is immutable';
    END LOOP;
    -- Legacy cloning uses ON COMMIT DROP temp maps, so exercise it once per transaction.
    v_clone := NULL;
    CALL game_engine.sp_definition_version_create(v_game, 'copy', 'Copy', NULL, v_clone);
    ASSERT game_engine.fn_definition_profile_mapping_get(v_clone) = v_mapping, 'Draft clone preserves mapping';
    ASSERT (game_engine.fn_engine_definition_units(v_clone)->0->>'maximumHullPoints')::int = 7;
END
$test$;
ROLLBACK;
