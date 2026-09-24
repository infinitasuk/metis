BEGIN;
DO $test$
DECLARE g bigint; v bigint; s bigint; human bigint; cpu bigint; alice bigint; bob bigint; rejected boolean;
BEGIN
 SELECT game_id,game_version_id INTO g,v FROM game_engine.game_version WHERE lifecycle_status='published' LIMIT 1;
 SELECT user_id INTO alice FROM game_instance.app_user WHERE username='alice';
 SELECT user_id INTO bob FROM game_instance.app_user WHERE username='bob';
 INSERT INTO game_instance.game_session(game_id,game_version_id,session_name,session_code,visibility,owner_user_id)
 VALUES(g,v,'Seat test','seat-test-'||md5(random()::text),'private',alice) RETURNING session_id INTO s;
 INSERT INTO game_instance.session_player(session_id,seat_number,display_name,player_kind)
 VALUES(s,1,'Human','human') RETURNING session_player_id INTO human;
 INSERT INTO game_instance.session_player(session_id,seat_number,display_name,player_kind)
 VALUES(s,2,'CPU','cpu') RETURNING session_player_id INTO cpu;
 ASSERT (SELECT user_id FROM game_instance.session_seat_owner WHERE session_player_id=human)=alice;
 ASSERT NOT EXISTS(SELECT 1 FROM game_instance.session_seat_owner WHERE session_player_id=cpu);
 ASSERT NOT game_instance.fn_session_can_view(s,bob,false);
 CALL game_instance.sp_seat_owner_assign(human,bob);
 ASSERT game_instance.fn_session_can_view(s,bob,false), 'Seat ownership grants private-session viewing';
 rejected:=false;
 BEGIN CALL game_instance.sp_seat_owner_assign(cpu,bob); EXCEPTION WHEN raise_exception THEN rejected:=true; END;
 ASSERT rejected, 'CPU seats cannot be assigned to humans';
 UPDATE game_instance.game_session SET status='running' WHERE session_id=s;
 rejected:=false;
 BEGIN CALL game_instance.sp_seat_owner_assign(human,alice); EXCEPTION WHEN raise_exception THEN rejected:=true; END;
 ASSERT rejected, 'A running seat cannot be taken over';
END $test$;
ROLLBACK;
