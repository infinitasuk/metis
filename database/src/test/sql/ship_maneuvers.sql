BEGIN;
DO $test$
DECLARE version bigint; snapshot jsonb; names text := '["CR90 Corvette A","Raider I-class Corvette"]';
BEGIN
 SELECT game_engine.fn_published_definition_id(game_id) INTO STRICT version FROM game_engine.game WHERE game_name='Star Wars: Armada';
 snapshot := game_engine.fn_rules_catalogue_maneuver_source(version,names,'bsdata-armada-ships-47bfca1','tts-armada-small-598f774')::jsonb;
 ASSERT jsonb_array_length(snapshot->'units')=2;
 ASSERT jsonb_path_query_array(snapshot,'$.units[*].pointsCost')='[44,44]'::jsonb;
 ASSERT jsonb_path_query_array(snapshot,'$.units[*].maximumHullPoints')='[4,4]'::jsonb;
 ASSERT snapshot #>> '{units,0,pointsSource,entryId}'='84f9-c194-d8cd-e718';
 ASSERT snapshot #>> '{units,1,pointsSource,entryId}'='e58d-8015-91be-811a';
 ASSERT snapshot #> '{units,0,maneuverChart}'='[[],[2],[1,2],[0,1,2],[0,1,1,2]]'::jsonb;
 ASSERT snapshot #> '{units,1,maneuverChart}'='[[],[2],[2,2],[0,1,1],[0,1,1,1]]'::jsonb;
 ASSERT snapshot #> '{units,0,maneuverGeometry}'='{"unit":"tts","width":2.796,"height":1.614,"segment":2.6265,"angle":22.5,"anchor":[0.618,1.127],"toolWidth":0.64}'::jsonb;
 ASSERT snapshot #>> '{units,1,maneuverSource,sha256}'='dd0ab900d0e0741dd40971a210d9e28164f70fb62657b023ccae0ff21b56f6a7';
 ASSERT snapshot #>> '{units,0,maneuverSource,calibration,0,sha256}'='370f99f90e736950ac9385fcfb07318a9a1e4c471a5b8f57c4131b61ef078359';
 ASSERT NOT (game_engine.fn_rules_catalogue_priced_source(version,names,'bsdata-armada-ships-47bfca1')::jsonb->'units'->0 ? 'maneuverChart');
 ASSERT (game_engine.fn_rules_catalogue_maneuver_source(version,names,'bsdata-armada-ships-47bfca1','missing')::jsonb #> '{units,0,maneuverChart}')='null'::jsonb;
 ASSERT game_engine.fn_rules_catalogue_maneuver_source(-1,names,'bsdata-armada-ships-47bfca1','tts-armada-small-598f774') IS NULL;
 BEGIN
  UPDATE game_engine.catalogue_unit_maneuver SET chart='[[],[0]]' WHERE maneuver_set='tts-armada-small-598f774';
  ASSERT false, 'Maneuver sources must be immutable';
 EXCEPTION WHEN SQLSTATE '55000' THEN NULL; END;
 BEGIN
  DELETE FROM game_engine.catalogue_unit_maneuver WHERE maneuver_set='tts-armada-small-598f774';
  ASSERT false, 'Maneuver sources cannot be removed';
 EXCEPTION WHEN SQLSTATE '55000' THEN NULL; END;
END
$test$;
ROLLBACK;
