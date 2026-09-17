-- Project colored firing dice and shield zones into the immutable engine definition.

BEGIN;

CREATE OR REPLACE FUNCTION game_engine.fn_engine_definition_units(p_definition_id bigint)
RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'code', uv.unit_variant_id::text,
        'displayName', uv.unit_variant_name,
        'classification', uc.unit_classification_name,
        'factionCode', f.faction_name,
        'maximumMovementDistance', COALESCE((
            SELECT NULLIF(c.characteristic_json->>'value', '')::double precision
            FROM game_engine.unit_variant_characteristic c
            WHERE c.unit_variant_id = uv.unit_variant_id
              AND c.characteristic_json->'source'->>'characteristic' IN ('Max Speed', 'Speed')
            ORDER BY CASE c.characteristic_json->'source'->>'characteristic'
                WHEN 'Max Speed' THEN 0 ELSE 1 END
            LIMIT 1
        ), 0),
        'maximumAttackRange', 300,
        'attackDamage', GREATEST(1, COALESCE((
            SELECT SUM((dice->>'count')::integer)
            FROM game_engine.unit_variant_characteristic c,
                 jsonb_array_elements(c.characteristic_json->'value') dice
            WHERE c.unit_variant_id = uv.unit_variant_id
              AND c.characteristic_json->'source'->>'characteristic' = 'Front Firing Arc'
        ), 1)),
        'attackDice', GREATEST(1, COALESCE((
            SELECT SUM((dice->>'count')::integer)
            FROM game_engine.unit_variant_characteristic c,
                 jsonb_array_elements(c.characteristic_json->'value') dice
            WHERE c.unit_variant_id = uv.unit_variant_id
              AND c.characteristic_json->'source'->>'characteristic' = 'Front Firing Arc'
        ), 1)),
        'maximumHullPoints', COALESCE((
            SELECT NULLIF(c.characteristic_json->>'value', '')::integer
            FROM game_engine.unit_variant_characteristic c
            WHERE c.unit_variant_id = uv.unit_variant_id
              AND c.characteristic_json->'source'->>'characteristic' = 'Hull Value'
            LIMIT 1
        ), 1),
        'maximumShieldPoints', COALESCE((
            SELECT SUM(NULLIF(c.characteristic_json->>'value', '')::integer)
            FROM game_engine.unit_variant_characteristic c
            WHERE c.unit_variant_id = uv.unit_variant_id
              AND c.characteristic_json->'source'->>'characteristic' LIKE '%Shield Value'
        ), 0),
        'defenseTokenCount', COALESCE((
            SELECT SUM(COALESCE((token->>'count')::integer, 1))
            FROM game_engine.unit_variant_characteristic c,
                 jsonb_array_elements(c.characteristic_json->'value') token
            WHERE c.unit_variant_id = uv.unit_variant_id
              AND c.characteristic_json->'source'->>'characteristic' = 'Defense Tokens'
        ), 0),
        'batteryArcs', COALESCE((
            SELECT jsonb_object_agg(
                lower(replace(c.characteristic_json->'source'->>'characteristic', ' Firing Arc', '')),
                c.characteristic_json->'value')
            FROM game_engine.unit_variant_characteristic c
            WHERE c.unit_variant_id = uv.unit_variant_id
              AND c.characteristic_json->'source'->>'characteristic' IN
                    ('Front Firing Arc', 'Left Firing Arc', 'Right Firing Arc', 'Rear Firing Arc')
        ), (
            SELECT jsonb_build_object('front', c.characteristic_json->'value')
            FROM game_engine.unit_variant_characteristic c
            WHERE c.unit_variant_id = uv.unit_variant_id
              AND c.characteristic_json->'source'->>'characteristic' = 'Battery'
            LIMIT 1
        )),
        'antiSquadronDice', COALESCE((
            SELECT c.characteristic_json->'value'
            FROM game_engine.unit_variant_characteristic c
            WHERE c.unit_variant_id = uv.unit_variant_id
              AND c.characteristic_json->'source'->>'characteristic' IN ('Anti-Squadron', 'Anti-Squadron Value')
            ORDER BY CASE c.characteristic_json->'source'->>'characteristic'
                WHEN 'Anti-Squadron' THEN 0 ELSE 1 END
            LIMIT 1
        ), '[]'::jsonb),
        'shieldZones', jsonb_build_object(
            'front', COALESCE((SELECT NULLIF(c.characteristic_json->>'value', '')::integer FROM game_engine.unit_variant_characteristic c WHERE c.unit_variant_id = uv.unit_variant_id AND c.characteristic_json->'source'->>'characteristic' = 'Front Shield Value' LIMIT 1), 0),
            'left', COALESCE((SELECT NULLIF(c.characteristic_json->>'value', '')::integer FROM game_engine.unit_variant_characteristic c WHERE c.unit_variant_id = uv.unit_variant_id AND c.characteristic_json->'source'->>'characteristic' = 'Left Shield Value' LIMIT 1), 0),
            'right', COALESCE((SELECT NULLIF(c.characteristic_json->>'value', '')::integer FROM game_engine.unit_variant_characteristic c WHERE c.unit_variant_id = uv.unit_variant_id AND c.characteristic_json->'source'->>'characteristic' = 'Right Shield Value' LIMIT 1), 0),
            'rear', COALESCE((SELECT NULLIF(c.characteristic_json->>'value', '')::integer FROM game_engine.unit_variant_characteristic c WHERE c.unit_variant_id = uv.unit_variant_id AND c.characteristic_json->'source'->>'characteristic' = 'Rear Shield Value' LIMIT 1), 0)
        ),
        'defenseTokenTypes', COALESCE((
            SELECT jsonb_agg(token->>'name' ORDER BY token->>'name')
            FROM game_engine.unit_variant_characteristic c,
                 jsonb_array_elements(c.characteristic_json->'value') token
            WHERE c.unit_variant_id = uv.unit_variant_id
              AND c.characteristic_json->'source'->>'characteristic' = 'Defense Tokens'
        ), '[]'::jsonb),
        'maneuverProfiles', COALESCE((
            SELECT jsonb_object_agg(p.speed::text, p.maximum_yaw_clicks ORDER BY p.speed)
            FROM game_engine.unit_variant_maneuver_profile p
            WHERE p.unit_variant_id = uv.unit_variant_id
        ), '{}'::jsonb),
        'baseRadius', CASE WHEN lower(uc.unit_classification_name) = 'squadron' THEN 25 ELSE 50 END
    ) ORDER BY uv.unit_variant_id), '[]'::jsonb)
    FROM game_engine.unit_variant uv
    JOIN game_engine.unit_type ut ON ut.unit_type_id = uv.unit_type_id
    JOIN game_engine.unit_classification uc ON uc.unit_classification_id = ut.unit_classification_id
    JOIN game_engine.faction f ON f.faction_id = uc.faction_id
    WHERE f.game_version_id = p_definition_id
$$;

CREATE OR REPLACE FUNCTION game_engine.fn_engine_definition_get(p_definition_id bigint)
RETURNS TABLE (
    game_id bigint,
    game_code text,
    game_name text,
    definition_version_id bigint,
    definition_version text,
    minimum_players integer,
    maximum_players integer,
    minimum_cpu integer,
    maximum_cpu integer,
    faction_codes text,
    unit_definitions text
) LANGUAGE sql STABLE AS $$
    SELECT gv.game_id,
           gv.version_code,
           COALESCE(gv.definition_game_name, g.game_name),
           gv.game_version_id,
           gv.version_code,
           COALESCE(gv.definition_min_players, g.minimum_number_of_players),
           COALESCE(gv.definition_max_players, g.maximum_number_of_players),
           COALESCE(gv.definition_min_cpu, g.minimum_number_of_cpu),
           COALESCE(gv.definition_max_cpu, g.maximum_number_of_cpu),
           COALESCE((SELECT jsonb_agg(f.faction_name ORDER BY f.faction_id)::text
                     FROM game_engine.faction f WHERE f.game_version_id = gv.game_version_id), '[]'),
           game_engine.fn_engine_definition_units(p_definition_id)::text
    FROM game_engine.game_version gv
    JOIN game_engine.game g ON g.game_id = gv.game_id
    WHERE gv.game_version_id = p_definition_id
$$;

COMMIT;
