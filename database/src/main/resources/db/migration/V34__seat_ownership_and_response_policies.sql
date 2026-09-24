CREATE TABLE game_instance.session_seat_owner (
 session_player_id bigint PRIMARY KEY REFERENCES game_instance.session_player(session_player_id) ON DELETE CASCADE,
 user_id bigint NOT NULL REFERENCES game_instance.app_user(user_id)
);
INSERT INTO game_instance.session_seat_owner(session_player_id,user_id)
SELECT p.session_player_id,s.owner_user_id FROM game_instance.session_player p
JOIN game_instance.game_session s USING(session_id)
WHERE p.player_kind='human' AND s.owner_user_id IS NOT NULL;

CREATE FUNCTION game_instance.trg_seat_owner_defaults() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.player_kind='human' THEN
  INSERT INTO game_instance.session_seat_owner(session_player_id,user_id)
  SELECT NEW.session_player_id,owner_user_id FROM game_instance.game_session WHERE session_id=NEW.session_id AND owner_user_id IS NOT NULL
  ON CONFLICT DO NOTHING;
 ELSE DELETE FROM game_instance.session_seat_owner WHERE session_player_id=NEW.session_player_id;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER seat_owner_defaults_trg AFTER INSERT OR UPDATE OF player_kind ON game_instance.session_player
FOR EACH ROW EXECUTE FUNCTION game_instance.trg_seat_owner_defaults();

CREATE PROCEDURE game_instance.sp_seat_owner_assign(p_player bigint,p_user bigint) LANGUAGE plpgsql AS $$
DECLARE v_status text; v_kind text;
BEGIN
 SELECT s.status,p.player_kind INTO v_status,v_kind FROM game_instance.game_session s
 JOIN game_instance.session_player p USING(session_id) WHERE p.session_player_id=p_player FOR UPDATE OF s;
 IF v_status IS DISTINCT FROM 'setup' OR v_kind IS DISTINCT FROM 'human' THEN RAISE EXCEPTION 'Only human seats in setup can be assigned'; END IF;
 IF NOT EXISTS(SELECT 1 FROM game_instance.app_user WHERE user_id=p_user AND active) THEN RAISE EXCEPTION 'An active user is required'; END IF;
 INSERT INTO game_instance.session_seat_owner VALUES(p_player,p_user)
 ON CONFLICT(session_player_id) DO UPDATE SET user_id=EXCLUDED.user_id;
END $$;
CREATE FUNCTION game_instance.fn_session_seats(p_session bigint)
RETURNS TABLE(session_player_id bigint,user_id bigint,display_name text,player_kind text)
LANGUAGE sql STABLE AS $$
 SELECT p.session_player_id,o.user_id,p.display_name,p.player_kind FROM game_instance.session_player p
 LEFT JOIN game_instance.session_seat_owner o USING(session_player_id) WHERE p.session_id=p_session ORDER BY p.seat_number
$$;
CREATE OR REPLACE FUNCTION game_instance.fn_session_can_view(p_session_id bigint,p_user_id bigint,p_is_admin boolean)
RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM game_instance.game_session s WHERE s.session_id=p_session_id AND
  (p_is_admin OR s.visibility='public' OR s.owner_user_id=p_user_id
   OR EXISTS(SELECT 1 FROM game_instance.session_invitation i WHERE i.session_id=s.session_id AND i.user_id=p_user_id)
   OR EXISTS(SELECT 1 FROM game_instance.session_player p JOIN game_instance.session_seat_owner o USING(session_player_id)
             WHERE p.session_id=s.session_id AND o.user_id=p_user_id)))
$$;

-- Controlled definition-data upgrade for new captures only. Existing session
-- captures retain their exact rules. Games without this resource binding opt in
-- to response policies explicitly through their draft turn structure.
ALTER TABLE game_engine.game_version DISABLE TRIGGER turn_structure_immutable_trg;
UPDATE game_engine.game_version SET turn_structure = turn_structure || '{
 "responses":[{"action":"attack_unit","targetUnitParameter":"targetUnitId","label":"Choose a defense response before resolving the attack",
 "declineParameters":{"declineDefense":true},"choices":[],"resourceField":"readyDefenseTokens","resourceParameter":"defenseTokenType"}]
}'::jsonb
WHERE turn_structure IS NOT NULL AND profile_mapping ? 'defenseTokens' AND NOT (turn_structure ? 'responses');
ALTER TABLE game_engine.game_version ENABLE TRIGGER turn_structure_immutable_trg;
