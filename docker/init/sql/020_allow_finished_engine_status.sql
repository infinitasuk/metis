-- Allow the authoritative engine's terminal status to persist.

BEGIN;

ALTER TABLE game_instance.game_session
    DROP CONSTRAINT IF EXISTS game_session_status_ck;

ALTER TABLE game_instance.game_session
    ADD CONSTRAINT game_session_status_ck
    CHECK (status IN ('setup', 'running', 'paused', 'finished', 'completed', 'cancelled'));

COMMIT;
