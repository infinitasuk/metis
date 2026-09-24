-- Keep entropy identity immutable and its cursor monotonic within the atomic command save.
CREATE OR REPLACE PROCEDURE game_instance.sp_rules_command_save(p_id uuid,p_expected bigint,p_checkpoint text,p_commit text,p_paused boolean,
 p_user bigint,p_actor text,p_command text,p_hash text,p_response text,p_http integer) LANGUAGE plpgsql AS $$
DECLARE previous bigint; original jsonb; expected_state jsonb; removed text[]; old_random jsonb; new_random jsonb;
BEGIN
 SELECT state_version,checkpoint_json::jsonb INTO STRICT previous,original FROM game_instance.rules_session WHERE session_id=p_id FOR UPDATE;
 IF previous<>p_expected THEN RAISE EXCEPTION 'Session version conflict' USING ERRCODE='40001'; END IF;
 IF p_commit IS NOT NULL THEN
  IF (p_commit::jsonb->>'beforeVersion')::bigint IS DISTINCT FROM previous OR (p_commit::jsonb->>'version')::bigint IS DISTINCT FROM previous+1
     OR (p_checkpoint::jsonb->>'version')::bigint IS DISTINCT FROM previous+1
     OR p_commit::jsonb->>'definitionFingerprint' IS DISTINCT FROM original->>'definitionFingerprint'
     OR p_commit::jsonb->>'runtimeVersion' IS DISTINCT FROM original->>'runtimeVersion'
     OR p_commit::jsonb->>'sessionId' IS DISTINCT FROM p_id::text THEN RAISE EXCEPTION 'Invalid transition identity/version'; END IF;
  SELECT COALESCE(array_agg(value),'{}'::text[]) INTO removed FROM jsonb_array_elements_text(p_commit::jsonb->'removedEntities');
  expected_state:=original||jsonb_build_object('version',previous+1,'globals',(original->'globals')||(p_commit::jsonb->'globals'),
      'entities',((original->'entities')-removed)||(p_commit::jsonb->'entities'),'control',p_commit::jsonb->'control','events',(original->'events')||(p_commit::jsonb->'events'));
  IF expected_state IS DISTINCT FROM p_checkpoint::jsonb THEN RAISE EXCEPTION 'Checkpoint and commit differ'; END IF;
  IF EXISTS(SELECT 1 FROM jsonb_array_elements(p_commit::jsonb->'events') WITH ORDINALITY e(value,n)
            WHERE (value->>'sequence')::bigint IS DISTINCT FROM jsonb_array_length(original->'events')+n) THEN RAISE EXCEPTION 'Event sequence mismatch'; END IF;
  old_random:=original#>'{control,mechanics,random}'; new_random:=p_checkpoint::jsonb#>'{control,mechanics,random}';
  IF old_random IS NOT NULL THEN
   IF jsonb_typeof(new_random) IS DISTINCT FROM 'object' OR (old_random-'position') IS DISTINCT FROM (new_random-'position')
      OR COALESCE((new_random->>'position')::bigint,-1)<(old_random->>'position')::bigint
   THEN RAISE EXCEPTION 'Random stream identity changed or cursor rewound' USING ERRCODE='22023'; END IF;
  ELSIF new_random IS NOT NULL THEN
   RAISE EXCEPTION 'Cannot attach a new random stream to an existing session' USING ERRCODE='22023';
  END IF;
  UPDATE game_instance.rules_session SET checkpoint_json=p_checkpoint,state_version=previous+1,status=p_checkpoint::jsonb->'control'->>'status',paused=p_paused,updated_at=now() WHERE session_id=p_id;
  INSERT INTO game_instance.rules_transition(session_id,state_version,commit_json,paused) VALUES(p_id,previous+1,p_commit,p_paused);
 END IF;
 INSERT INTO game_instance.rules_command(session_id,user_id,actor_key,command_id,request_hash,response_json,http_status)
 VALUES(p_id,p_user,p_actor,p_command,p_hash,p_response,p_http);
END $$;
