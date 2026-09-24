BEGIN;
DO $test$
DECLARE v_game bigint; v_revision bigint; v_draft bigint; v_session bigint; v_json text; v_rejected boolean; v_structure jsonb;
BEGIN
 SELECT game_id, game_version_id, turn_structure INTO v_game, v_revision, v_structure
 FROM game_engine.game_version WHERE lifecycle_status = 'published' AND turn_structure IS NOT NULL LIMIT 1;
 ASSERT v_revision IS NOT NULL;
 INSERT INTO game_engine.game_version(game_id, version_code, version_name, lifecycle_status)
 VALUES(v_game, 'snapshot-test-' || md5(random()::text), 'Snapshot test', 'draft') RETURNING game_version_id INTO v_draft;
 ASSERT game_engine.fn_definition_turn_structure_get(v_draft) = v_structure;
 v_rejected := false;
 BEGIN UPDATE game_engine.game_version SET turn_structure = '{}' WHERE game_version_id = v_revision;
 EXCEPTION WHEN raise_exception THEN v_rejected := true; END;
 ASSERT v_rejected, 'Published sequencing must remain immutable';
 INSERT INTO game_instance.game_session(game_id, game_version_id, session_name, session_code)
 VALUES(v_game, v_revision, 'Snapshot test', 'snapshot-test-' || md5(random()::text)) RETURNING session_id INTO v_session;
 v_json := jsonb_build_object('definitionVersionId', v_revision)::text;
 CALL game_instance.sp_session_definition_snapshot_create(v_session, 1, 'activation-v1', v_json, 'test-checksum');
 ASSERT (SELECT definition_json FROM game_instance.fn_session_definition_snapshot_get(v_session)) = v_json;
 v_rejected := false;
 BEGIN UPDATE game_instance.session_definition_snapshot SET definition_json = '{}' WHERE session_id = v_session;
 EXCEPTION WHEN raise_exception THEN v_rejected := true; END;
 ASSERT v_rejected, 'Captured definition cannot be changed';
 v_rejected := false;
 BEGIN CALL game_instance.sp_session_definition_snapshot_create(v_session, 1, 'activation-v1', v_json, 'test-checksum');
 EXCEPTION WHEN unique_violation THEN v_rejected := true; END;
 ASSERT v_rejected, 'Definition cannot be captured twice';
END $test$;
ROLLBACK;
