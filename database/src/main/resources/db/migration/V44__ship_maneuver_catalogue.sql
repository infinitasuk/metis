-- New source sets and projection: historical costs, legacy charts and captures retain their contracts.
INSERT INTO game_engine.catalogue_unit_cost
 (cost_set,game_name,faction_name,classification,unit_name,catalogue_file,catalogue_revision,points_cost,unique_key,provenance)
VALUES
 ('bsdata-armada-ships-47bfca1','Star Wars: Armada','Rebel Alliance','Ship','CR90 Corvette A','Rebel Fleet.cat','55',44,'',
  '{"repository":"https://github.com/BSData/star-wars-armada","commit":"47bfca1bddea459678568e5ee820597a433d9b41","file":"Rebel Fleet.cat","revision":"55","entryId":"84f9-c194-d8cd-e718","sha256":"d663dc3c24b4e5d53d0884475cc608879ed16183491dc04b6ad32f094bc5865c","url":"https://raw.githubusercontent.com/BSData/star-wars-armada/47bfca1bddea459678568e5ee820597a433d9b41/Rebel%20Fleet.cat","rawPoints":"44.0","licence":"CC BY-SA 4.0"}'),
 ('bsdata-armada-ships-47bfca1','Star Wars: Armada','Galactic Empire','Ship','Raider I-class Corvette','Imperial Navy.cat','56',44,'',
  '{"repository":"https://github.com/BSData/star-wars-armada","commit":"47bfca1bddea459678568e5ee820597a433d9b41","file":"Imperial Navy.cat","revision":"56","entryId":"e58d-8015-91be-811a","sha256":"803dde91e1db1b6be8bed6af70267cbe93e64f9c25cc53ca13da8dac926aae8c","url":"https://raw.githubusercontent.com/BSData/star-wars-armada/47bfca1bddea459678568e5ee820597a433d9b41/Imperial%20Navy.cat","rawPoints":"44.0","licence":"CC BY-SA 4.0"}');

CREATE TABLE game_engine.catalogue_unit_maneuver (
 maneuver_set text NOT NULL,
 game_name text NOT NULL,
 faction_name text NOT NULL,
 classification text NOT NULL,
 unit_name text NOT NULL,
 chart jsonb NOT NULL CHECK (jsonb_typeof(chart)='array' AND jsonb_array_length(chart) BETWEEN 2 AND 9 AND chart->0='[]'::jsonb),
 geometry jsonb NOT NULL CHECK (jsonb_typeof(geometry)='object'),
 provenance jsonb NOT NULL,
 PRIMARY KEY (maneuver_set,game_name,faction_name,classification,unit_name),
 CHECK ((jsonb_typeof(provenance)='object'
   AND provenance ?& ARRAY['repository','commit','file','sha256','url','licence','calibration']
   AND provenance->>'commit' ~ '^[0-9a-f]{40}$'
   AND provenance->>'sha256' ~ '^[0-9a-f]{64}$'
   AND jsonb_typeof(provenance->'calibration')='array') IS TRUE)
);
CREATE TRIGGER catalogue_unit_maneuver_immutable BEFORE UPDATE OR DELETE ON game_engine.catalogue_unit_maneuver
 FOR EACH ROW EXECUTE FUNCTION game_instance.trg_rules_record_immutable();

-- Unscaled TTS geometry. Forward/across axes map to board x/y, positive yaw clockwise.
-- Small base half extents: 1.398 forward, 0.807 across; attachment: (1.398-.78, +/-(.807+.32)).
-- The 0.64-wide capsule is an explicitly authored training collision envelope, not a plastic-tool mesh.
WITH profiles(faction,name,chart,file,sha) AS (VALUES
 ('Rebel Alliance','CR90 Corvette A','[[],[2],[1,2],[0,1,2],[0,1,1,2]]'::jsonb,'TTS_armada/src/defs/rebel/ships.ttslua','9b71c226d0fffe9c29e45d8e991e6822a2f21ef4e212a15fddeca36ffd4dd572'),
 ('Galactic Empire','Raider I-class Corvette','[[],[2],[2,2],[0,1,1],[0,1,1,1]]'::jsonb,'TTS_armada/src/defs/empire/ships.ttslua','dd0ab900d0e0741dd40971a210d9e28164f70fb62657b023ccae0ff21b56f6a7'))
INSERT INTO game_engine.catalogue_unit_maneuver(maneuver_set,game_name,faction_name,classification,unit_name,chart,geometry,provenance)
SELECT 'tts-armada-small-598f774','Star Wars: Armada',faction,'Ship',name,chart,
 '{"unit":"tts","width":2.796,"height":1.614,"segment":2.6265,"angle":22.5,"anchor":[0.618,1.127],"toolWidth":0.64}'::jsonb,
 jsonb_build_object('repository','https://github.com/Valadian/TabletopSimulatorIncludeDir','commit','598f7741a7e3d09dd0e362b47914dd5d54ec1fe9',
  'file',file,'sha256',sha,'url','https://raw.githubusercontent.com/Valadian/TabletopSimulatorIncludeDir/598f7741a7e3d09dd0e362b47914dd5d54ec1fe9/'||file,
  'licence','not specified','profile',name,'scale',1,'toolPolicy','training-capsule-0.64','calibration',
  '[{"file":"TTS_armada/src/Magic Ruler.ttslua","sha256":"370f99f90e736950ac9385fcfb07318a9a1e4c471a5b8f57c4131b61ef078359","symbols":"extendSelf, moveChild, Action_Move"},
    {"file":"TTS_armada/src/Global.ttslua","sha256":"ef118a870bd6b504b599774f932f1d941e79b8b2bc59e3c82e063900a9590c18","symbols":"ship_size, moveRuler"}]'::jsonb)
FROM profiles;

CREATE FUNCTION game_engine.fn_rules_catalogue_maneuver_source(p_version bigint,p_names text,p_cost_set text,p_maneuver_set text)
RETURNS text LANGUAGE sql STABLE AS $$
 WITH snapshot AS (SELECT game_engine.fn_rules_catalogue_priced_source(p_version,p_names,p_cost_set)::jsonb AS data)
 SELECT (data || jsonb_build_object('maneuverSet',p_maneuver_set,'units',
  (SELECT COALESCE(jsonb_agg(profile.value || jsonb_build_object('maneuverChart',m.chart,'maneuverGeometry',m.geometry,'maneuverSource',m.provenance)
    ORDER BY profile.ordinality),'[]'::jsonb)
   FROM jsonb_array_elements(data->'units') WITH ORDINALITY profile
   LEFT JOIN game_engine.catalogue_unit_maneuver m ON m.maneuver_set=p_maneuver_set AND m.game_name=data->>'gameName'
    AND m.faction_name=profile.value->>'factionCode' AND m.classification=profile.value->>'classification' AND m.unit_name=profile.value->>'displayName')))::text
 FROM snapshot WHERE data IS NOT NULL
$$;
