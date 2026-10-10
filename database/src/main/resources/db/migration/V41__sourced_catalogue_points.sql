-- Additive sourced costs. The development estimates and old import projections retain their contracts.
CREATE TABLE game_engine.catalogue_unit_cost (
 cost_set text NOT NULL,
 game_name text NOT NULL,
 faction_name text NOT NULL,
 classification text NOT NULL,
 unit_name text NOT NULL,
 catalogue_file text NOT NULL,
 catalogue_revision text NOT NULL,
 points_cost integer NOT NULL CHECK (points_cost > 0),
 unique_key text NOT NULL,
 provenance jsonb NOT NULL,
 PRIMARY KEY (cost_set, game_name, faction_name, classification, unit_name),
 CHECK ((jsonb_typeof(provenance) = 'object'
   AND provenance ?& ARRAY['repository','commit','file','revision','entryId','sha256','url','rawPoints','licence']
   AND provenance->>'commit' ~ '^[0-9a-f]{40}$'
   AND provenance->>'sha256' ~ '^[0-9a-f]{64}$'
   AND provenance->>'file' = catalogue_file
   AND provenance->>'revision' = catalogue_revision
   AND (provenance->>'rawPoints')::numeric = points_cost) IS TRUE)
);
CREATE TRIGGER catalogue_unit_cost_immutable BEFORE UPDATE OR DELETE ON game_engine.catalogue_unit_cost
 FOR EACH ROW EXECUTE FUNCTION game_instance.trg_rules_record_immutable();

INSERT INTO game_engine.catalogue_unit_cost
 (cost_set, game_name, faction_name, classification, unit_name, catalogue_file, catalogue_revision, points_cost, unique_key, provenance)
VALUES
 ('bsdata-armada-47bfca1', 'Star Wars: Armada', 'Galactic Empire', 'Squadron', 'TIE Fighter Squadron', 'Imperial Navy.cat', '56', 8, '',
  '{"repository":"https://github.com/BSData/star-wars-armada","commit":"47bfca1bddea459678568e5ee820597a433d9b41","file":"Imperial Navy.cat","revision":"56","entryId":"baa4-494e-4b1e-f5f2","sha256":"803dde91e1db1b6be8bed6af70267cbe93e64f9c25cc53ca13da8dac926aae8c","url":"https://raw.githubusercontent.com/BSData/star-wars-armada/47bfca1bddea459678568e5ee820597a433d9b41/Imperial%20Navy.cat","rawPoints":"8.0","licence":"CC BY-SA 4.0"}'),
 ('bsdata-armada-47bfca1', 'Star Wars: Armada', 'Rebel Alliance', 'Squadron', 'X-Wing Squadron', 'Rebel Fleet.cat', '55', 13, '',
  '{"repository":"https://github.com/BSData/star-wars-armada","commit":"47bfca1bddea459678568e5ee820597a433d9b41","file":"Rebel Fleet.cat","revision":"55","entryId":"55f0-0a64-b063-acc8","sha256":"d663dc3c24b4e5d53d0884475cc608879ed16183491dc04b6ad32f094bc5865c","url":"https://raw.githubusercontent.com/BSData/star-wars-armada/47bfca1bddea459678568e5ee820597a433d9b41/Rebel%20Fleet.cat","rawPoints":"13.0","licence":"CC BY-SA 4.0"}');

CREATE FUNCTION game_engine.fn_rules_catalogue_priced_source(p_version bigint, p_names text, p_cost_set text)
RETURNS text LANGUAGE sql STABLE AS $$
 WITH snapshot AS (SELECT game_engine.fn_rules_catalogue_source(p_version, p_names)::jsonb AS data)
 SELECT (data || jsonb_build_object('costSet', p_cost_set, 'units',
  (SELECT COALESCE(jsonb_agg(profile.value || jsonb_build_object(
     'pointsCost', cost.points_cost, 'uniqueKey', cost.unique_key, 'pointsSource', cost.provenance)
     ORDER BY profile.ordinality), '[]'::jsonb)
   FROM jsonb_array_elements(data->'units') WITH ORDINALITY profile
   LEFT JOIN game_engine.catalogue_unit_cost cost
     ON cost.cost_set = p_cost_set AND cost.game_name = data->>'gameName'
    AND cost.faction_name = profile.value->>'factionCode'
    AND cost.classification = profile.value->>'classification'
    AND cost.unit_name = profile.value->>'displayName'
    AND EXISTS (SELECT 1 FROM game_engine.unit_variant_characteristic characteristic
      WHERE characteristic.unit_variant_id = (profile.value->>'code')::bigint
        AND characteristic.characteristic_json #>> '{source,catalogue}' = cost.catalogue_file
        AND characteristic.characteristic_json #>> '{source,revision}' = cost.catalogue_revision
        AND characteristic.characteristic_json #>> '{source,profile}' = cost.unit_name))))::text
 FROM snapshot WHERE data IS NOT NULL
$$;
