-- Development point costs for unit variants used by fleet construction.
-- The imported catalogue does not currently carry unit costs, so these values
-- are derived from the combat profile until canonical costs are available.

BEGIN;

CREATE TABLE IF NOT EXISTS game_engine.unit_variant_points (
    game_version_id bigint NOT NULL REFERENCES game_engine.game_version (game_version_id),
    unit_variant_id bigint NOT NULL REFERENCES game_engine.unit_variant (unit_variant_id),
    points_cost integer NOT NULL,
    PRIMARY KEY (game_version_id, unit_variant_id),
    CONSTRAINT unit_variant_points_cost_ck CHECK (points_cost > 0)
);

WITH profile AS (
    SELECT f.game_version_id, uv.unit_variant_id,
           lower(uc.unit_classification_name) = 'squadron' AS squadron,
           COALESCE(MAX(NULLIF(uvc.characteristic_json->>'value', '')::numeric)
                    FILTER (WHERE ct.characteristic_type_name IN ('Hull', 'Hull Value')), 1) AS hull,
           COALESCE(SUM(NULLIF(uvc.characteristic_json->>'value', '')::numeric)
                    FILTER (WHERE ct.characteristic_type_name LIKE '%Shield Value'), 0) AS shields,
           COALESCE(MAX(NULLIF(uvc.characteristic_json->>'value', '')::numeric)
                    FILTER (WHERE ct.characteristic_type_name IN ('Speed', 'Max Speed')), 1) AS speed,
           COALESCE(MAX(NULLIF(uvc.characteristic_json->>'value', '')::numeric)
                    FILTER (WHERE ct.characteristic_type_name = 'Command'), 0) AS command_value,
           COALESCE(MAX(NULLIF(uvc.characteristic_json->>'value', '')::numeric)
                    FILTER (WHERE ct.characteristic_type_name = 'Engineering'), 0) AS engineering,
           COALESCE(MAX(NULLIF(uvc.characteristic_json->>'value', '')::numeric)
                    FILTER (WHERE ct.characteristic_type_name = 'Squadron'), 0) AS squadron_value
    FROM game_engine.unit_variant uv
    JOIN game_engine.unit_type ut ON ut.unit_type_id = uv.unit_type_id
    JOIN game_engine.unit_classification uc ON uc.unit_classification_id = ut.unit_classification_id
    JOIN game_engine.faction f ON f.faction_id = uc.faction_id
    LEFT JOIN game_engine.unit_variant_characteristic uvc ON uvc.unit_variant_id = uv.unit_variant_id
    LEFT JOIN game_engine.characteristic_type ct ON ct.characteristic_type_id = uvc.characteristic_type_id
    GROUP BY f.game_version_id, uv.unit_variant_id, uc.unit_classification_name
)
INSERT INTO game_engine.unit_variant_points (game_version_id, unit_variant_id, points_cost)
SELECT game_version_id, unit_variant_id,
       CASE WHEN squadron
            THEN GREATEST(5, ROUND(hull * 2 + speed * 2 + squadron_value))
            ELSE GREATEST(20, ROUND(hull * 8 + shields * 3 + speed * 2
                                    + command_value * 2 + engineering * 2 + squadron_value * 2))
       END
FROM profile
ON CONFLICT (game_version_id, unit_variant_id) DO UPDATE
SET points_cost = EXCLUDED.points_cost;

COMMIT;
