BEGIN;
DO $test$
DECLARE version bigint; snapshot jsonb; older jsonb; missing jsonb;
 names text := '["CR90 Corvette A","Raider I-class Corvette"]';
BEGIN
 SELECT game_engine.fn_published_definition_id(game_id) INTO STRICT version FROM game_engine.game WHERE game_name='Star Wars: Armada';
 older := game_engine.fn_rules_catalogue_maneuver_source(version,names,'bsdata-armada-ships-47bfca1','tts-armada-small-598f774')::jsonb;
 snapshot := game_engine.fn_rules_catalogue_command_source(version,names,'bsdata-armada-ships-47bfca1','tts-armada-small-598f774')::jsonb;
 ASSERT jsonb_path_query_array(snapshot,'$.units[*].commandValue')='[1,1]'::jsonb;
 ASSERT snapshot #>> '{units,0,commandSource,rawValue}'='1';
 ASSERT snapshot #>> '{units,0,commandSource,characteristic,catalogue}'='Rebel Fleet.cat';
 ASSERT snapshot #>> '{units,1,commandSource,characteristic,catalogue}'='Imperial Navy.cat';
 ASSERT snapshot #>> '{units,0,commandSource,characteristic,profile}'='CR90 Corvette A';
 ASSERT snapshot #>> '{units,1,commandSource,characteristic,profile}'='Raider I-class Corvette';
 ASSERT snapshot #>> '{units,1,commandSource,characteristic,revision}'='56';
 ASSERT snapshot #>> '{units,0,commandSource,provenance,commit}'='47bfca1bddea459678568e5ee820597a433d9b41';
 ASSERT snapshot #> '{units,0,commandSource,provenance}'=snapshot #> '{units,0,pointsSource}';
 ASSERT snapshot #> '{units,1,maneuverChart}'=older #> '{units,1,maneuverChart}';
 ASSERT NOT (older->'units'->0 ? 'commandSource'), 'The earlier capture projection is frozen';
 missing := game_engine.fn_rules_catalogue_command_source(version,names,'missing','tts-armada-small-598f774')::jsonb;
 ASSERT missing #> '{units,0,commandSource}'='null'::jsonb;
 ASSERT missing #> '{units,0,commandValue}'='null'::jsonb, 'Unmatched command data cannot use legacy defaults';
 ASSERT game_engine.fn_rules_catalogue_command_source(-1,names,'bsdata-armada-ships-47bfca1','tts-armada-small-598f774') IS NULL;
END
$test$;
ROLLBACK;
