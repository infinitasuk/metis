-- Remove the experimental table used while exploring game structure ideas.
-- The tracked game-mode model is the current replacement.

BEGIN;

DROP TABLE IF EXISTS game_engine.game_structure;

COMMIT;
