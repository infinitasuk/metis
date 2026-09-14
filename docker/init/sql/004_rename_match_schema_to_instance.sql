-- Rename the runtime schema introduced as game_match before the terminology
-- was settled. Fresh databases already create game_instance, so this is a
-- no-op for them.

BEGIN;

DO $$
BEGIN
    IF to_regnamespace('game_match') IS NOT NULL
       AND to_regnamespace('game_instance') IS NULL THEN
        ALTER SCHEMA game_match RENAME TO game_instance;
    END IF;
END
$$;

COMMENT ON SCHEMA game_instance IS
    'User game instances, runtime state, actions and results.';

COMMIT;
