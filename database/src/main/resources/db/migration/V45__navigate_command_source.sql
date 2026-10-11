-- Strict command-value capture is additive: V44 and existing imported fingerprints stay frozen.
CREATE FUNCTION game_engine.fn_rules_catalogue_command_source(p_version bigint,p_names text,p_cost_set text,p_maneuver_set text)
RETURNS text LANGUAGE sql STABLE AS $$
 WITH snapshot AS (SELECT game_engine.fn_rules_catalogue_maneuver_source(p_version,p_names,p_cost_set,p_maneuver_set)::jsonb AS data)
 SELECT (data || jsonb_build_object('units',
  (SELECT COALESCE(jsonb_agg(profile.value || jsonb_build_object(
    'commandValue',CASE WHEN jsonb_array_length(command.values)=1 THEN command.values #> '{0,value}' END,
    'commandSource',CASE WHEN jsonb_array_length(command.values)=1 THEN jsonb_build_object(
      'characteristic',command.values #> '{0,source}','rawValue',command.values #> '{0,raw_value}','provenance',profile.value->'pointsSource') END)
    ORDER BY profile.ordinality),'[]'::jsonb)
   FROM jsonb_array_elements(data->'units') WITH ORDINALITY profile
   CROSS JOIN LATERAL (
    SELECT jsonb_agg(c.characteristic_json) AS values FROM game_engine.unit_variant_characteristic c
    WHERE c.unit_variant_id=(profile.value->>'code')::bigint
      AND c.characteristic_json #>> '{source,characteristic}'='Command'
      AND c.characteristic_json #>> '{source,catalogue}'=profile.value #>> '{pointsSource,file}'
      AND c.characteristic_json #>> '{source,revision}'=profile.value #>> '{pointsSource,revision}'
      AND c.characteristic_json #>> '{source,profile}'=profile.value->>'displayName'
   ) command)))::text
 FROM snapshot WHERE data IS NOT NULL
$$;
