-- Preserve typed command values and defense-token counts in projected unit profiles.

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
        'commandValue', COALESCE((
            SELECT NULLIF(c.characteristic_json->>'value', '')::integer
            FROM game_engine.unit_variant_characteristic c
            WHERE c.unit_variant_id = uv.unit_variant_id
              AND c.characteristic_json->'source'->>'characteristic' = 'Command'
            LIMIT 1
        ), 0),
        'engineeringValue', COALESCE((
            SELECT NULLIF(c.characteristic_json->>'value', '')::integer
            FROM game_engine.unit_variant_characteristic c
            WHERE c.unit_variant_id = uv.unit_variant_id
              AND c.characteristic_json->'source'->>'characteristic' = 'Engineering'
            LIMIT 1
        ), 0),
        'squadronValue', COALESCE((
            SELECT NULLIF(c.characteristic_json->>'value', '')::integer
            FROM game_engine.unit_variant_characteristic c
            WHERE c.unit_variant_id = uv.unit_variant_id
              AND c.characteristic_json->'source'->>'characteristic' = 'Squadron'
            LIMIT 1
        ), 0),
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
        'defenseTokenCounts', COALESCE((
            SELECT jsonb_object_agg(token_name, token_count)
            FROM (
                SELECT lower(token->>'name') AS token_name,
                       SUM(COALESCE((token->>'count')::integer, 1)) AS token_count
                FROM game_engine.unit_variant_characteristic c,
                     jsonb_array_elements(c.characteristic_json->'value') token
                WHERE c.unit_variant_id = uv.unit_variant_id
                  AND c.characteristic_json->'source'->>'characteristic' = 'Defense Tokens'
                GROUP BY lower(token->>'name')
            ) tokens
        ), '{}'::jsonb),
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

COMMIT;
