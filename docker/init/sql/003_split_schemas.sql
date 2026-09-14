-- Move databases created by the original public-schema model into the new
-- static-definition schema. This is a no-op for fresh databases because
-- 001_create_static_model.sql already creates the tables in game_engine.

BEGIN;

CREATE SCHEMA IF NOT EXISTS game_engine;
CREATE SCHEMA IF NOT EXISTS game_instance;

ALTER TABLE IF EXISTS public.game SET SCHEMA game_engine;
ALTER TABLE IF EXISTS public.faction SET SCHEMA game_engine;
ALTER TABLE IF EXISTS public.unit_classification SET SCHEMA game_engine;
ALTER TABLE IF EXISTS public.unit_type SET SCHEMA game_engine;
ALTER TABLE IF EXISTS public.unit_variant SET SCHEMA game_engine;
ALTER TABLE IF EXISTS public.terrain_type SET SCHEMA game_engine;
ALTER TABLE IF EXISTS public.game_rule SET SCHEMA game_engine;
ALTER TABLE IF EXISTS public."match" SET SCHEMA game_engine;
ALTER TABLE IF EXISTS public.match_attribute SET SCHEMA game_engine;
ALTER TABLE IF EXISTS public.characteristic_type SET SCHEMA game_engine;
ALTER TABLE IF EXISTS public.unit_type_characteristic SET SCHEMA game_engine;
ALTER TABLE IF EXISTS public.unit_variant_characteristic SET SCHEMA game_engine;

COMMENT ON SCHEMA game_engine IS
    'Versioned game definitions, catalogue content, rules and scenario configuration.';
COMMENT ON SCHEMA game_instance IS
    'User match instances, runtime state, actions and results.';

COMMIT;
