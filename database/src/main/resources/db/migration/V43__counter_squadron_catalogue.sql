-- Extend the supported roster through a new immutable source set.
INSERT INTO game_engine.catalogue_unit_cost
 (cost_set,game_name,faction_name,classification,unit_name,catalogue_file,catalogue_revision,points_cost,unique_key,provenance)
SELECT 'bsdata-armada-counter-47bfca1',game_name,faction_name,classification,unit_name,catalogue_file,catalogue_revision,points_cost,unique_key,provenance
FROM game_engine.catalogue_unit_cost WHERE cost_set='bsdata-armada-47bfca1';

INSERT INTO game_engine.catalogue_unit_cost
 (cost_set,game_name,faction_name,classification,unit_name,catalogue_file,catalogue_revision,points_cost,unique_key,provenance)
VALUES
 ('bsdata-armada-counter-47bfca1','Star Wars: Armada','Rebel Alliance','Squadron','A-Wing Squadron','Rebel Fleet.cat','55',11,'',
  '{"repository":"https://github.com/BSData/star-wars-armada","commit":"47bfca1bddea459678568e5ee820597a433d9b41","file":"Rebel Fleet.cat","revision":"55","entryId":"8557-fcfa-10e8-d19b","sha256":"d663dc3c24b4e5d53d0884475cc608879ed16183491dc04b6ad32f094bc5865c","url":"https://raw.githubusercontent.com/BSData/star-wars-armada/47bfca1bddea459678568e5ee820597a433d9b41/Rebel%20Fleet.cat","rawPoints":"11.0","licence":"CC BY-SA 4.0"}'),
 ('bsdata-armada-counter-47bfca1','Star Wars: Armada','Galactic Empire','Squadron','TIE Interceptor Squadron','Imperial Navy.cat','56',11,'',
  '{"repository":"https://github.com/BSData/star-wars-armada","commit":"47bfca1bddea459678568e5ee820597a433d9b41","file":"Imperial Navy.cat","revision":"56","entryId":"af57-88c9-9924-c93f","sha256":"803dde91e1db1b6be8bed6af70267cbe93e64f9c25cc53ca13da8dac926aae8c","url":"https://raw.githubusercontent.com/BSData/star-wars-armada/47bfca1bddea459678568e5ee820597a433d9b41/Imperial%20Navy.cat","rawPoints":"11.0","licence":"CC BY-SA 4.0"}');

CREATE FUNCTION game_engine.fn_rules_catalogue_keyword_source(p_version bigint,p_names text,p_cost_set text)
RETURNS text LANGUAGE sql STABLE AS $$
 WITH snapshot AS (SELECT game_engine.fn_rules_catalogue_priced_source(p_version,p_names,p_cost_set)::jsonb AS data)
 SELECT (data || jsonb_build_object('units',
  (SELECT COALESCE(jsonb_agg(profile.value || jsonb_build_object(
    'keywords',CASE WHEN jsonb_array_length(characteristics.keywords)=1 THEN characteristics.keywords #> '{0,value,keywords}' END,
    'keywordSource',CASE WHEN jsonb_array_length(characteristics.keywords)=1 THEN characteristics.keywords #> '{0,source}' END,
    'squadronAbility',CASE WHEN jsonb_array_length(characteristics.abilities)=1 THEN characteristics.abilities #> '{0,value}' END,
    'abilitySource',CASE WHEN jsonb_array_length(characteristics.abilities)=1 THEN characteristics.abilities #> '{0,source}' END)
    ORDER BY profile.ordinality),'[]'::jsonb)
   FROM jsonb_array_elements(data->'units') WITH ORDINALITY profile
   CROSS JOIN LATERAL (
    SELECT jsonb_agg(c.characteristic_json) FILTER (WHERE t.characteristic_type_name='Keywords') AS keywords,
           jsonb_agg(c.characteristic_json) FILTER (WHERE t.characteristic_type_name='Squadron Ability') AS abilities
    FROM game_engine.unit_variant_characteristic c JOIN game_engine.characteristic_type t USING(characteristic_type_id)
    WHERE c.unit_variant_id=(profile.value->>'code')::bigint
      AND c.characteristic_json #>> '{source,catalogue}'=profile.value #>> '{pointsSource,file}'
      AND c.characteristic_json #>> '{source,revision}'=profile.value #>> '{pointsSource,revision}'
      AND c.characteristic_json #>> '{source,profile}'=profile.value->>'displayName'
   ) characteristics)))::text
 FROM snapshot WHERE data IS NOT NULL
$$;
