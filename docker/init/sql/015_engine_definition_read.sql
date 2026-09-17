-- Read contract used by the framework-independent engine adapter.

BEGIN;

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
    maximum_cpu integer
) LANGUAGE sql STABLE AS $$
    SELECT gv.game_id,
           gv.version_code,
           COALESCE(gv.definition_game_name, g.game_name),
           gv.game_version_id,
           gv.version_code,
           COALESCE(gv.definition_min_players, g.minimum_number_of_players),
           COALESCE(gv.definition_max_players, g.maximum_number_of_players),
           COALESCE(gv.definition_min_cpu, g.minimum_number_of_cpu),
           COALESCE(gv.definition_max_cpu, g.maximum_number_of_cpu)
    FROM game_engine.game_version gv
    JOIN game_engine.game g ON g.game_id = gv.game_id
    WHERE gv.game_version_id = p_definition_id
$$;

COMMIT;
