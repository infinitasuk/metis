BEGIN;
DO $test$
DECLARE version bigint; snapshot jsonb; old_projection jsonb;
 names text := '["TIE Fighter Squadron","X-Wing Squadron","A-Wing Squadron","TIE Interceptor Squadron"]';
BEGIN
 SELECT game_engine.fn_published_definition_id(game_id) INTO STRICT version FROM game_engine.game WHERE game_name='Star Wars: Armada';
 ASSERT (SELECT count(*) FROM game_engine.catalogue_unit_cost WHERE cost_set='bsdata-armada-47bfca1')=2;
 ASSERT (SELECT count(*) FROM game_engine.catalogue_unit_cost WHERE cost_set='bsdata-armada-counter-47bfca1')=4;
 snapshot := game_engine.fn_rules_catalogue_keyword_source(version,names,'bsdata-armada-counter-47bfca1')::jsonb;
 ASSERT jsonb_array_length(snapshot->'units')=4;
 ASSERT jsonb_path_query_array(snapshot,'$.units[*].pointsCost')='[8,13,11,11]'::jsonb;
 ASSERT snapshot #>> '{units,2,pointsSource,entryId}'='8557-fcfa-10e8-d19b';
 ASSERT snapshot #>> '{units,3,pointsSource,entryId}'='af57-88c9-9924-c93f';
 ASSERT snapshot #> '{units,2,keywords}'='["Counter 2"]'::jsonb;
 ASSERT snapshot #> '{units,3,keywords}'='["Counter 2","Swarm"]'::jsonb;
 ASSERT snapshot #>> '{units,2,keywordSource,catalogue}'='Rebel Fleet.cat';
 ASSERT snapshot #>> '{units,3,keywordSource,revision}'='56';
 ASSERT snapshot #>> '{units,2,maximumHullPoints}'='4';
 ASSERT snapshot #>> '{units,3,maximumHullPoints}'='3';
 ASSERT (snapshot #>> '{units,2,maximumMovementDistance}')::numeric=5;
 ASSERT (snapshot #>> '{units,3,maximumMovementDistance}')::numeric=5;
 ASSERT snapshot #> '{units,2,squadronAbility}'='null'::jsonb;
 ASSERT snapshot #>> '{units,2,abilitySource,characteristic}'='Squadron Ability';
 old_projection := game_engine.fn_rules_catalogue_priced_source(version,names,'bsdata-armada-47bfca1')::jsonb;
 ASSERT old_projection #> '{units,2,pointsCost}'='null'::jsonb, 'Old cost sets are not broadened implicitly';
 ASSERT NOT (old_projection->'units'->0 ? 'keywords'), 'Old import fingerprints keep the old projection';
 ASSERT game_engine.fn_rules_catalogue_keyword_source(-1,names,'bsdata-armada-counter-47bfca1') IS NULL;
END
$test$;
ROLLBACK;
