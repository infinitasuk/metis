CREATE TABLE game_engine.rules_catalogue_entry (
 entry_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 game_id bigint NOT NULL REFERENCES game_engine.game,
 source_version_id bigint NOT NULL REFERENCES game_engine.game_version,
 pack_code text NOT NULL,mode_code text NOT NULL,entry_name text NOT NULL,
 definition_id bigint NOT NULL UNIQUE REFERENCES game_engine.rules_definition,
 source_fingerprint text NOT NULL CHECK(source_fingerprint ~ '^[0-9a-f]{64}$'),
 source_snapshot text NOT NULL CHECK(jsonb_typeof(source_snapshot::jsonb)='object'),scope_json jsonb NOT NULL CHECK(jsonb_typeof(scope_json)='object'),
 UNIQUE(source_version_id,pack_code,source_fingerprint,mode_code)
);
CREATE TRIGGER rules_catalogue_immutable BEFORE UPDATE OR DELETE ON game_engine.rules_catalogue_entry
 FOR EACH ROW EXECUTE FUNCTION game_instance.trg_rules_record_immutable();
CREATE FUNCTION game_engine.fn_rules_catalogue_list() RETURNS SETOF game_engine.rules_catalogue_entry
 LANGUAGE sql STABLE AS $$ SELECT * FROM game_engine.rules_catalogue_entry ORDER BY entry_id $$;
CREATE FUNCTION game_engine.fn_rules_catalogue_lock(p_version bigint) RETURNS bigint LANGUAGE sql VOLATILE AS $$
 SELECT game_version_id FROM game_engine.game_version WHERE game_version_id=p_version AND lifecycle_status='published' FOR UPDATE
$$;
CREATE FUNCTION game_engine.fn_rules_catalogue_source(p_version bigint,p_names text) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT jsonb_build_object('gameId',g.game_id,'gameName',g.game_name,'versionId',v.game_version_id,'versionCode',v.version_code,
  'units',COALESCE((SELECT jsonb_agg(profile.value ORDER BY requested.ordinality)
   FROM jsonb_array_elements(p_names::jsonb) WITH ORDINALITY requested,
        jsonb_array_elements(d.unit_definitions::jsonb) profile
   WHERE profile.value->>'displayName'=requested.value#>>'{}'),'[]'::jsonb))::text
 FROM game_engine.game_version v JOIN game_engine.game g USING(game_id)
 CROSS JOIN LATERAL game_engine.fn_engine_definition_get(v.game_version_id) d
 WHERE v.game_version_id=p_version AND v.lifecycle_status='published'
$$;
CREATE PROCEDURE game_engine.sp_rules_catalogue_register(p_version bigint,p_pack text,p_mode text,p_name text,p_definition bigint,p_hash text,p_snapshot text,p_scope text)
 LANGUAGE plpgsql AS $$
DECLARE game bigint;
BEGIN
 SELECT game_id INTO STRICT game FROM game_engine.game_version WHERE game_version_id=p_version AND lifecycle_status='published' FOR SHARE;
 IF NOT EXISTS(SELECT 1 FROM game_engine.rules_definition WHERE definition_id=p_definition AND lifecycle='published')
    OR (p_snapshot::jsonb->>'versionId')::bigint IS DISTINCT FROM p_version
    OR (p_snapshot::jsonb->>'gameId')::bigint IS DISTINCT FROM game THEN RAISE EXCEPTION 'Invalid catalogue registration'; END IF;
 INSERT INTO game_engine.rules_catalogue_entry(game_id,source_version_id,pack_code,mode_code,entry_name,definition_id,source_fingerprint,source_snapshot,scope_json)
 VALUES(game,p_version,p_pack,p_mode,p_name,p_definition,p_hash,p_snapshot,p_scope::jsonb);
END $$;
