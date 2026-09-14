-- Scope game-owned catalogue rows to a definition revision.
-- Child rows inherit the revision through their versioned parent rows.

BEGIN;

ALTER TABLE game_engine.game_version
    ADD CONSTRAINT game_version_id_game_uq UNIQUE (game_version_id, game_id);

ALTER TABLE game_engine.faction ADD COLUMN IF NOT EXISTS game_version_id bigint;
ALTER TABLE game_engine.terrain_type ADD COLUMN IF NOT EXISTS game_version_id bigint;
ALTER TABLE game_engine.game_rule ADD COLUMN IF NOT EXISTS game_version_id bigint;
ALTER TABLE game_engine.game_mode ADD COLUMN IF NOT EXISTS game_version_id bigint;
ALTER TABLE game_engine.phase_definition ADD COLUMN IF NOT EXISTS game_version_id bigint;
ALTER TABLE game_engine.action_definition ADD COLUMN IF NOT EXISTS game_version_id bigint;
ALTER TABLE game_engine.scenario_definition ADD COLUMN IF NOT EXISTS game_version_id bigint;
ALTER TABLE game_engine.board_definition ADD COLUMN IF NOT EXISTS game_version_id bigint;
ALTER TABLE game_engine.game_object_type ADD COLUMN IF NOT EXISTS game_version_id bigint;
ALTER TABLE game_engine.game_object ADD COLUMN IF NOT EXISTS game_version_id bigint;

UPDATE game_engine.faction f
SET game_version_id = gv.game_version_id
FROM game_engine.game_version gv
WHERE gv.game_id = f.game_id AND gv.lifecycle_status = 'published';
UPDATE game_engine.terrain_type t
SET game_version_id = gv.game_version_id
FROM game_engine.game_version gv
WHERE gv.game_id = t.game_id AND gv.lifecycle_status = 'published';
UPDATE game_engine.game_rule r
SET game_version_id = gv.game_version_id
FROM game_engine.game_version gv
WHERE gv.game_id = r.game_id AND gv.lifecycle_status = 'published';
UPDATE game_engine.game_mode m
SET game_version_id = gv.game_version_id
FROM game_engine.game_version gv
WHERE gv.game_id = m.game_id AND gv.lifecycle_status = 'published';
UPDATE game_engine.phase_definition p
SET game_version_id = gv.game_version_id
FROM game_engine.game_version gv
WHERE gv.game_id = p.game_id AND gv.lifecycle_status = 'published';
UPDATE game_engine.action_definition a
SET game_version_id = gv.game_version_id
FROM game_engine.game_version gv
WHERE gv.game_id = a.game_id AND gv.lifecycle_status = 'published';
UPDATE game_engine.scenario_definition s
SET game_version_id = gv.game_version_id
FROM game_engine.game_version gv
WHERE gv.game_id = s.game_id AND gv.lifecycle_status = 'published';
UPDATE game_engine.board_definition b
SET game_version_id = gv.game_version_id
FROM game_engine.game_version gv
WHERE gv.game_id = b.game_id AND gv.lifecycle_status = 'published';
UPDATE game_engine.game_object_type ot
SET game_version_id = gv.game_version_id
FROM game_engine.game_version gv
WHERE gv.game_id = ot.game_id AND gv.lifecycle_status = 'published';
UPDATE game_engine.game_object o
SET game_version_id = gv.game_version_id
FROM game_engine.game_version gv
WHERE gv.game_id = o.game_id AND gv.lifecycle_status = 'published';

ALTER TABLE game_engine.faction ALTER COLUMN game_version_id SET NOT NULL;
ALTER TABLE game_engine.terrain_type ALTER COLUMN game_version_id SET NOT NULL;
ALTER TABLE game_engine.game_rule ALTER COLUMN game_version_id SET NOT NULL;
ALTER TABLE game_engine.game_mode ALTER COLUMN game_version_id SET NOT NULL;
ALTER TABLE game_engine.phase_definition ALTER COLUMN game_version_id SET NOT NULL;
ALTER TABLE game_engine.action_definition ALTER COLUMN game_version_id SET NOT NULL;
ALTER TABLE game_engine.scenario_definition ALTER COLUMN game_version_id SET NOT NULL;
ALTER TABLE game_engine.board_definition ALTER COLUMN game_version_id SET NOT NULL;
ALTER TABLE game_engine.game_object_type ALTER COLUMN game_version_id SET NOT NULL;
ALTER TABLE game_engine.game_object ALTER COLUMN game_version_id SET NOT NULL;

ALTER TABLE game_engine.game_mode DROP CONSTRAINT IF EXISTS game_mode_game_code_uq;
ALTER TABLE game_engine.phase_definition DROP CONSTRAINT IF EXISTS phase_game_code_uq;
ALTER TABLE game_engine.action_definition DROP CONSTRAINT IF EXISTS action_game_code_uq;
ALTER TABLE game_engine.scenario_definition DROP CONSTRAINT IF EXISTS scenario_game_code_uq;
ALTER TABLE game_engine.board_definition DROP CONSTRAINT IF EXISTS board_game_code_uq;
ALTER TABLE game_engine.game_object_type DROP CONSTRAINT IF EXISTS game_object_type_game_code_uq;
ALTER TABLE game_engine.game_object DROP CONSTRAINT IF EXISTS game_object_game_code_uq;

CREATE UNIQUE INDEX IF NOT EXISTS game_mode_definition_code_uq
    ON game_engine.game_mode (game_version_id, game_mode_code);
CREATE UNIQUE INDEX IF NOT EXISTS phase_definition_code_uq
    ON game_engine.phase_definition (game_version_id, phase_code);
CREATE UNIQUE INDEX IF NOT EXISTS action_definition_code_uq
    ON game_engine.action_definition (game_version_id, action_code);
CREATE UNIQUE INDEX IF NOT EXISTS scenario_definition_code_uq
    ON game_engine.scenario_definition (game_version_id, scenario_code);
CREATE UNIQUE INDEX IF NOT EXISTS board_definition_code_uq
    ON game_engine.board_definition (game_version_id, board_code);
CREATE UNIQUE INDEX IF NOT EXISTS game_object_type_definition_code_uq
    ON game_engine.game_object_type (game_version_id, object_type_code);
CREATE UNIQUE INDEX IF NOT EXISTS game_object_definition_code_uq
    ON game_engine.game_object (game_version_id, object_code);

ALTER TABLE game_engine.faction
    ADD CONSTRAINT faction_definition_fk FOREIGN KEY (game_version_id, game_id)
    REFERENCES game_engine.game_version (game_version_id, game_id);
ALTER TABLE game_engine.terrain_type
    ADD CONSTRAINT terrain_definition_fk FOREIGN KEY (game_version_id, game_id)
    REFERENCES game_engine.game_version (game_version_id, game_id);
ALTER TABLE game_engine.game_rule
    ADD CONSTRAINT rule_definition_fk FOREIGN KEY (game_version_id, game_id)
    REFERENCES game_engine.game_version (game_version_id, game_id);
ALTER TABLE game_engine.game_mode
    ADD CONSTRAINT mode_definition_fk FOREIGN KEY (game_version_id, game_id)
    REFERENCES game_engine.game_version (game_version_id, game_id);
ALTER TABLE game_engine.phase_definition
    ADD CONSTRAINT phase_definition_fk FOREIGN KEY (game_version_id, game_id)
    REFERENCES game_engine.game_version (game_version_id, game_id);
ALTER TABLE game_engine.action_definition
    ADD CONSTRAINT action_definition_fk FOREIGN KEY (game_version_id, game_id)
    REFERENCES game_engine.game_version (game_version_id, game_id);
ALTER TABLE game_engine.scenario_definition
    ADD CONSTRAINT scenario_definition_fk FOREIGN KEY (game_version_id, game_id)
    REFERENCES game_engine.game_version (game_version_id, game_id);
ALTER TABLE game_engine.board_definition
    ADD CONSTRAINT board_definition_fk FOREIGN KEY (game_version_id, game_id)
    REFERENCES game_engine.game_version (game_version_id, game_id);
ALTER TABLE game_engine.game_object_type
    ADD CONSTRAINT object_type_definition_fk FOREIGN KEY (game_version_id, game_id)
    REFERENCES game_engine.game_version (game_version_id, game_id);
ALTER TABLE game_engine.game_object
    ADD CONSTRAINT object_definition_fk FOREIGN KEY (game_version_id, game_id)
    REFERENCES game_engine.game_version (game_version_id, game_id);

CREATE OR REPLACE FUNCTION game_engine.fn_published_definition_id(p_game_id bigint)
RETURNS bigint LANGUAGE sql STABLE AS $$
    SELECT game_version_id
    FROM game_engine.game_version
    WHERE game_id = p_game_id AND lifecycle_status = 'published'
    ORDER BY published_at DESC NULLS LAST, game_version_id DESC
    LIMIT 1
$$;

CREATE OR REPLACE PROCEDURE game_engine.sp_definition_version_create(
    IN p_game_id bigint,
    IN p_version_code text,
    IN p_version_name text,
    IN p_source_revision text,
    INOUT p_definition_id bigint
) LANGUAGE plpgsql AS $$
DECLARE
    v_source_id bigint;
    v_new_id bigint;
    v_new_row_id bigint;
    v_row record;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM game_engine.game WHERE game_id = p_game_id) THEN
        RAISE EXCEPTION 'Game % does not exist', p_game_id USING ERRCODE = '23503';
    END IF;

    INSERT INTO game_engine.game_version (
        game_id, version_code, version_name, source_revision, lifecycle_status, is_active
    ) VALUES (p_game_id, p_version_code, p_version_name, p_source_revision, 'draft', false)
    RETURNING game_version_id INTO v_new_id;

    SELECT game_engine.fn_published_definition_id(p_game_id) INTO v_source_id;
    IF v_source_id IS NULL THEN
        p_definition_id := v_new_id;
        RETURN;
    END IF;

    CREATE TEMP TABLE tmp_faction_map (old_id bigint PRIMARY KEY, new_id bigint) ON COMMIT DROP;
    CREATE TEMP TABLE tmp_classification_map (old_id bigint PRIMARY KEY, new_id bigint) ON COMMIT DROP;
    CREATE TEMP TABLE tmp_type_map (old_id bigint PRIMARY KEY, new_id bigint) ON COMMIT DROP;
    CREATE TEMP TABLE tmp_variant_map (old_id bigint PRIMARY KEY, new_id bigint) ON COMMIT DROP;
    CREATE TEMP TABLE tmp_rule_map (old_id bigint PRIMARY KEY, new_id bigint) ON COMMIT DROP;
    CREATE TEMP TABLE tmp_mode_map (old_id bigint PRIMARY KEY, new_id bigint) ON COMMIT DROP;
    CREATE TEMP TABLE tmp_phase_map (old_id bigint PRIMARY KEY, new_id bigint) ON COMMIT DROP;
    CREATE TEMP TABLE tmp_scenario_map (old_id bigint PRIMARY KEY, new_id bigint) ON COMMIT DROP;
    CREATE TEMP TABLE tmp_board_map (old_id bigint PRIMARY KEY, new_id bigint) ON COMMIT DROP;
    CREATE TEMP TABLE tmp_format_map (old_id bigint PRIMARY KEY, new_id bigint) ON COMMIT DROP;
    CREATE TEMP TABLE tmp_object_type_map (old_id bigint PRIMARY KEY, new_id bigint) ON COMMIT DROP;
    CREATE TEMP TABLE tmp_object_map (old_id bigint PRIMARY KEY, new_id bigint) ON COMMIT DROP;

    FOR v_row IN SELECT * FROM game_engine.faction WHERE game_version_id = v_source_id LOOP
        INSERT INTO game_engine.faction (game_id, game_version_id, faction_name, faction_description)
        VALUES (p_game_id, v_new_id, v_row.faction_name, v_row.faction_description)
        RETURNING faction_id INTO v_new_row_id;
        INSERT INTO tmp_faction_map VALUES (v_row.faction_id, v_new_row_id);
    END LOOP;
    FOR v_row IN SELECT uc.* FROM game_engine.unit_classification uc
                 JOIN tmp_faction_map m ON m.old_id = uc.faction_id LOOP
        INSERT INTO game_engine.unit_classification
            (faction_id, unit_classification_name, unit_classification_description)
        VALUES ((SELECT new_id FROM tmp_faction_map WHERE old_id = v_row.faction_id),
                v_row.unit_classification_name, v_row.unit_classification_description)
        RETURNING unit_classification_id INTO v_new_row_id;
        INSERT INTO tmp_classification_map VALUES (v_row.unit_classification_id, v_new_row_id);
    END LOOP;
    FOR v_row IN SELECT ut.* FROM game_engine.unit_type ut
                 JOIN tmp_classification_map m ON m.old_id = ut.unit_classification_id LOOP
        INSERT INTO game_engine.unit_type
            (unit_classification_id, unit_type_name, unit_type_description)
        VALUES ((SELECT new_id FROM tmp_classification_map WHERE old_id = v_row.unit_classification_id),
                v_row.unit_type_name, v_row.unit_type_description)
        RETURNING unit_type_id INTO v_new_row_id;
        INSERT INTO tmp_type_map VALUES (v_row.unit_type_id, v_new_row_id);
    END LOOP;
    FOR v_row IN SELECT uv.* FROM game_engine.unit_variant uv
                 JOIN tmp_type_map m ON m.old_id = uv.unit_type_id LOOP
        INSERT INTO game_engine.unit_variant
            (unit_type_id, unit_variant_name, unit_variant_description)
        VALUES ((SELECT new_id FROM tmp_type_map WHERE old_id = v_row.unit_type_id),
                v_row.unit_variant_name, v_row.unit_variant_description)
        RETURNING unit_variant_id INTO v_new_row_id;
        INSERT INTO tmp_variant_map VALUES (v_row.unit_variant_id, v_new_row_id);
    END LOOP;

    FOR v_row IN SELECT * FROM game_engine.terrain_type WHERE game_version_id = v_source_id LOOP
        INSERT INTO game_engine.terrain_type
            (game_id, game_version_id, terrain_type_name, terrain_type, default_characteristic_json)
        VALUES (p_game_id, v_new_id, v_row.terrain_type_name, v_row.terrain_type, v_row.default_characteristic_json);
    END LOOP;
    FOR v_row IN SELECT * FROM game_engine.game_rule WHERE game_version_id = v_source_id LOOP
        INSERT INTO game_engine.game_rule
            (game_id, game_version_id, game_rule_name, game_rule_description, game_rule_characteristic_json)
        VALUES (p_game_id, v_new_id, v_row.game_rule_name, v_row.game_rule_description, v_row.game_rule_characteristic_json)
        RETURNING game_rule_id INTO v_new_row_id;
        INSERT INTO tmp_rule_map VALUES (v_row.game_rule_id, v_new_row_id);
    END LOOP;
    FOR v_row IN SELECT * FROM game_engine.game_mode WHERE game_version_id = v_source_id LOOP
        INSERT INTO game_engine.game_mode
            (game_id, game_version_id, parent_game_mode_id, game_mode_code, game_mode_name,
             game_mode_description, mode_config_json)
        VALUES (p_game_id, v_new_id, null, v_row.game_mode_code, v_row.game_mode_name,
                v_row.game_mode_description, v_row.mode_config_json)
        RETURNING game_mode_id INTO v_new_row_id;
        INSERT INTO tmp_mode_map VALUES (v_row.game_mode_id, v_new_row_id);
    END LOOP;
    FOR v_row IN SELECT * FROM game_engine.game_mode WHERE game_version_id = v_source_id LOOP
        UPDATE game_engine.game_mode target
        SET parent_game_mode_id = parent_map.new_id
        FROM tmp_mode_map parent_map
        WHERE target.game_mode_id = (SELECT new_id FROM tmp_mode_map WHERE old_id = v_row.game_mode_id)
          AND parent_map.old_id = v_row.parent_game_mode_id;
    END LOOP;
    FOR v_row IN SELECT mr.* FROM game_engine.game_mode_rule mr
                 JOIN tmp_mode_map m ON m.old_id = mr.game_mode_id LOOP
        INSERT INTO game_engine.game_mode_rule
            (game_mode_id, game_rule_id, rule_order, rule_config_json)
        VALUES ((SELECT new_id FROM tmp_mode_map WHERE old_id = v_row.game_mode_id),
                (SELECT new_id FROM tmp_rule_map WHERE old_id = v_row.game_rule_id),
                v_row.rule_order, v_row.rule_config_json);
    END LOOP;

    FOR v_row IN SELECT * FROM game_engine.phase_definition WHERE game_version_id = v_source_id LOOP
        INSERT INTO game_engine.phase_definition
            (game_id, game_version_id, phase_code, phase_name, phase_description, phase_config_json)
        VALUES (p_game_id, v_new_id, v_row.phase_code, v_row.phase_name,
                v_row.phase_description, v_row.phase_config_json)
        RETURNING phase_id INTO v_new_row_id;
        INSERT INTO tmp_phase_map VALUES (v_row.phase_id, v_new_row_id);
    END LOOP;
    FOR v_row IN SELECT mp.* FROM game_engine.game_mode_phase mp
                 JOIN tmp_mode_map mm ON mm.old_id = mp.game_mode_id
                 JOIN tmp_phase_map pm ON pm.old_id = mp.phase_id LOOP
        INSERT INTO game_engine.game_mode_phase
            (game_mode_id, phase_id, phase_order, phase_config_json)
        VALUES ((SELECT new_id FROM tmp_mode_map WHERE old_id = v_row.game_mode_id),
                (SELECT new_id FROM tmp_phase_map WHERE old_id = v_row.phase_id),
                v_row.phase_order, v_row.phase_config_json);
    END LOOP;
    FOR v_row IN SELECT * FROM game_engine.action_definition WHERE game_version_id = v_source_id LOOP
        INSERT INTO game_engine.action_definition
            (game_id, game_version_id, action_code, action_name, action_description, action_config_json)
        VALUES (p_game_id, v_new_id, v_row.action_code, v_row.action_name,
                v_row.action_description, v_row.action_config_json);
    END LOOP;
    FOR v_row IN SELECT * FROM game_engine.scenario_definition WHERE game_version_id = v_source_id LOOP
        INSERT INTO game_engine.scenario_definition
            (game_id, game_version_id, scenario_code, scenario_name, scenario_description, scenario_config_json)
        VALUES (p_game_id, v_new_id, v_row.scenario_code, v_row.scenario_name,
                v_row.scenario_description, v_row.scenario_config_json)
        RETURNING scenario_id INTO v_new_row_id;
        INSERT INTO tmp_scenario_map VALUES (v_row.scenario_id, v_new_row_id);
    END LOOP;
    FOR v_row IN SELECT ms.* FROM game_engine.game_mode_scenario ms
                 JOIN tmp_mode_map mm ON mm.old_id = ms.game_mode_id
                 JOIN tmp_scenario_map sm ON sm.old_id = ms.scenario_id LOOP
        INSERT INTO game_engine.game_mode_scenario
            (game_mode_id, scenario_id, is_default, scenario_order, scenario_config_json)
        VALUES ((SELECT new_id FROM tmp_mode_map WHERE old_id = v_row.game_mode_id),
                (SELECT new_id FROM tmp_scenario_map WHERE old_id = v_row.scenario_id),
                v_row.is_default, v_row.scenario_order, v_row.scenario_config_json);
    END LOOP;
    FOR v_row IN SELECT * FROM game_engine.board_definition WHERE game_version_id = v_source_id LOOP
        INSERT INTO game_engine.board_definition
            (game_id, game_version_id, board_code, board_name, width, height, coordinate_system, board_config_json)
        VALUES (p_game_id, v_new_id, v_row.board_code, v_row.board_name, v_row.width,
                v_row.height, v_row.coordinate_system, v_row.board_config_json)
        RETURNING board_definition_id INTO v_new_row_id;
        INSERT INTO tmp_board_map VALUES (v_row.board_definition_id, v_new_row_id);
    END LOOP;
    FOR v_row IN SELECT sb.* FROM game_engine.scenario_board sb
                 JOIN tmp_scenario_map sm ON sm.old_id = sb.scenario_id
                 JOIN tmp_board_map bm ON bm.old_id = sb.board_definition_id LOOP
        INSERT INTO game_engine.scenario_board
            (scenario_id, board_definition_id, board_role, board_config_json)
        VALUES ((SELECT new_id FROM tmp_scenario_map WHERE old_id = v_row.scenario_id),
                (SELECT new_id FROM tmp_board_map WHERE old_id = v_row.board_definition_id),
                v_row.board_role, v_row.board_config_json);
    END LOOP;

    FOR v_row IN SELECT ff.* FROM game_engine.fleet_format ff
                 JOIN tmp_mode_map mm ON mm.old_id = ff.game_mode_id LOOP
        INSERT INTO game_engine.fleet_format
            (game_mode_id, format_code, format_name, points_limit, format_config_json)
        VALUES ((SELECT new_id FROM tmp_mode_map WHERE old_id = v_row.game_mode_id),
                v_row.format_code, v_row.format_name, v_row.points_limit, v_row.format_config_json)
        RETURNING fleet_format_id INTO v_new_row_id;
        INSERT INTO tmp_format_map VALUES (v_row.fleet_format_id, v_new_row_id);
    END LOOP;
    FOR v_row IN SELECT fff.* FROM game_engine.fleet_format_faction fff
                 JOIN tmp_format_map fm ON fm.old_id = fff.fleet_format_id
                 JOIN tmp_faction_map facm ON facm.old_id = fff.faction_id LOOP
        INSERT INTO game_engine.fleet_format_faction (fleet_format_id, faction_id)
        VALUES ((SELECT new_id FROM tmp_format_map WHERE old_id = v_row.fleet_format_id),
                (SELECT new_id FROM tmp_faction_map WHERE old_id = v_row.faction_id));
    END LOOP;
    FOR v_row IN SELECT ful.* FROM game_engine.fleet_unit_limit ful
                 JOIN tmp_format_map fm ON fm.old_id = ful.fleet_format_id LOOP
        INSERT INTO game_engine.fleet_unit_limit
            (fleet_format_id, unit_classification_id, unit_type_id, minimum_count, maximum_count, limit_config_json)
        VALUES ((SELECT new_id FROM tmp_format_map WHERE old_id = v_row.fleet_format_id),
                (SELECT new_id FROM tmp_classification_map WHERE old_id = v_row.unit_classification_id),
                (SELECT new_id FROM tmp_type_map WHERE old_id = v_row.unit_type_id),
                v_row.minimum_count, v_row.maximum_count, v_row.limit_config_json);
    END LOOP;
    FOR v_row IN SELECT utc.* FROM game_engine.unit_type_characteristic utc
                 JOIN tmp_type_map tm ON tm.old_id = utc.unit_type_id LOOP
        INSERT INTO game_engine.unit_type_characteristic
            (unit_type_id, characteristic_type_id, characteristic_json)
        VALUES ((SELECT new_id FROM tmp_type_map WHERE old_id = v_row.unit_type_id),
                v_row.characteristic_type_id, v_row.characteristic_json);
    END LOOP;
    FOR v_row IN SELECT uvc.* FROM game_engine.unit_variant_characteristic uvc
                 JOIN tmp_variant_map vm ON vm.old_id = uvc.unit_variant_id LOOP
        INSERT INTO game_engine.unit_variant_characteristic
            (unit_variant_id, characteristic_type_id, characteristic_json)
        VALUES ((SELECT new_id FROM tmp_variant_map WHERE old_id = v_row.unit_variant_id),
                v_row.characteristic_type_id, v_row.characteristic_json);
    END LOOP;

    FOR v_row IN SELECT * FROM game_engine.game_object_type WHERE game_version_id = v_source_id LOOP
        INSERT INTO game_engine.game_object_type
            (game_id, game_version_id, object_type_code, object_type_name, object_kind, validation_schema_json)
        VALUES (p_game_id, v_new_id, v_row.object_type_code, v_row.object_type_name,
                v_row.object_kind, v_row.validation_schema_json)
        RETURNING game_object_type_id INTO v_new_row_id;
        INSERT INTO tmp_object_type_map VALUES (v_row.game_object_type_id, v_new_row_id);
    END LOOP;
    FOR v_row IN SELECT * FROM game_engine.game_object WHERE game_version_id = v_source_id LOOP
        INSERT INTO game_engine.game_object
            (game_id, game_version_id, game_object_type_id, object_code, object_name,
             object_description, display_text, object_data_json, source_reference_json)
        VALUES (p_game_id, v_new_id,
                (SELECT new_id FROM tmp_object_type_map WHERE old_id = v_row.game_object_type_id),
                v_row.object_code, v_row.object_name, v_row.object_description,
                v_row.display_text, v_row.object_data_json, v_row.source_reference_json)
        RETURNING game_object_id INTO v_new_row_id;
        INSERT INTO tmp_object_map VALUES (v_row.game_object_id, v_new_row_id);
    END LOOP;
    FOR v_row IN SELECT r.* FROM game_engine.game_object_relation r
                 JOIN tmp_object_map sm ON sm.old_id = r.source_game_object_id
                 JOIN tmp_object_map tm ON tm.old_id = r.target_game_object_id LOOP
        INSERT INTO game_engine.game_object_relation
            (source_game_object_id, target_game_object_id, relation_type, relation_config_json)
        VALUES ((SELECT new_id FROM tmp_object_map WHERE old_id = v_row.source_game_object_id),
                (SELECT new_id FROM tmp_object_map WHERE old_id = v_row.target_game_object_id),
                v_row.relation_type, v_row.relation_config_json);
    END LOOP;
    FOR v_row IN SELECT e.* FROM game_engine.game_object_eligibility e
                 JOIN tmp_object_map om ON om.old_id = e.game_object_id LOOP
        INSERT INTO game_engine.game_object_eligibility
            (game_object_id, faction_id, unit_classification_id, unit_type_id,
             unit_variant_id, game_mode_id, eligibility_type, condition_json)
        VALUES ((SELECT new_id FROM tmp_object_map WHERE old_id = v_row.game_object_id),
                (SELECT new_id FROM tmp_faction_map WHERE old_id = v_row.faction_id),
                (SELECT new_id FROM tmp_classification_map WHERE old_id = v_row.unit_classification_id),
                (SELECT new_id FROM tmp_type_map WHERE old_id = v_row.unit_type_id),
                (SELECT new_id FROM tmp_variant_map WHERE old_id = v_row.unit_variant_id),
                (SELECT new_id FROM tmp_mode_map WHERE old_id = v_row.game_mode_id),
                v_row.eligibility_type, v_row.condition_json);
    END LOOP;
    FOR v_row IN SELECT uvo.* FROM game_engine.unit_variant_game_object uvo
                 JOIN tmp_variant_map vm ON vm.old_id = uvo.unit_variant_id
                 JOIN tmp_object_map om ON om.old_id = uvo.game_object_id LOOP
        INSERT INTO game_engine.unit_variant_game_object
            (unit_variant_id, game_object_id, usage_code, quantity, usage_config_json)
        VALUES ((SELECT new_id FROM tmp_variant_map WHERE old_id = v_row.unit_variant_id),
                (SELECT new_id FROM tmp_object_map WHERE old_id = v_row.game_object_id),
                v_row.usage_code, v_row.quantity, v_row.usage_config_json);
    END LOOP;
    FOR v_row IN SELECT mgo.* FROM game_engine.game_mode_game_object mgo
                 JOIN tmp_mode_map mm ON mm.old_id = mgo.game_mode_id
                 JOIN tmp_object_map om ON om.old_id = mgo.game_object_id LOOP
        INSERT INTO game_engine.game_mode_game_object
            (game_mode_id, game_object_id, usage_code, quantity, usage_config_json)
        VALUES ((SELECT new_id FROM tmp_mode_map WHERE old_id = v_row.game_mode_id),
                (SELECT new_id FROM tmp_object_map WHERE old_id = v_row.game_object_id),
                v_row.usage_code, v_row.quantity, v_row.usage_config_json);
    END LOOP;
    FOR v_row IN SELECT sgo.* FROM game_engine.scenario_game_object sgo
                 JOIN tmp_scenario_map sm ON sm.old_id = sgo.scenario_id
                 JOIN tmp_object_map om ON om.old_id = sgo.game_object_id LOOP
        INSERT INTO game_engine.scenario_game_object
            (scenario_id, game_object_id, usage_code, quantity, usage_config_json)
        VALUES ((SELECT new_id FROM tmp_scenario_map WHERE old_id = v_row.scenario_id),
                (SELECT new_id FROM tmp_object_map WHERE old_id = v_row.game_object_id),
                v_row.usage_code, v_row.quantity, v_row.usage_config_json);
    END LOOP;

    p_definition_id := v_new_id;
END
$$;

COMMIT;
