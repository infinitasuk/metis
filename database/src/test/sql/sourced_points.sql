-- Rolled-back source/cost contract checks. Uses no development database state.
BEGIN;
DO $test$
DECLARE
 version bigint; snapshot jsonb; old_snapshot jsonb; denied boolean;
 names text := '["TIE Fighter Squadron","X-Wing Squadron"]';
BEGIN
 SELECT game_engine.fn_published_definition_id(game_id) INTO STRICT version
 FROM game_engine.game WHERE game_name = 'Star Wars: Armada';
 old_snapshot := game_engine.fn_rules_catalogue_source(version, names)::jsonb;
 snapshot := game_engine.fn_rules_catalogue_priced_source(version, names, 'bsdata-armada-47bfca1')::jsonb;
 ASSERT snapshot #>> '{units,0,pointsCost}' = '8';
 ASSERT snapshot #>> '{units,1,pointsCost}' = '13';
 ASSERT snapshot #>> '{units,0,uniqueKey}' = '';
 ASSERT snapshot #>> '{units,0,pointsSource,entryId}' = 'baa4-494e-4b1e-f5f2';
 ASSERT snapshot #>> '{units,1,pointsSource,entryId}' = '55f0-0a64-b063-acc8';
 ASSERT snapshot #>> '{units,0,pointsSource,revision}' = '56';
 ASSERT snapshot #>> '{units,1,pointsSource,revision}' = '55';
 ASSERT NOT ((old_snapshot->'units'->0) ? 'pointsCost'), 'Old catalogue fingerprint inputs must stay stable';
 ASSERT game_engine.fn_rules_catalogue_source(version, names)::jsonb = old_snapshot;

 denied := false;
 BEGIN
  UPDATE game_engine.catalogue_unit_cost SET points_cost = 1 WHERE cost_set = 'bsdata-armada-47bfca1';
 EXCEPTION WHEN SQLSTATE '55000' THEN denied := true;
 END;
 ASSERT denied, 'Sourced point revisions are immutable';
 denied := false;
 BEGIN
  INSERT INTO game_engine.catalogue_unit_cost
  SELECT 'bad-cost', game_name, faction_name, classification, unit_name, catalogue_file, catalogue_revision,
         1, unique_key, provenance FROM game_engine.catalogue_unit_cost WHERE cost_set='bsdata-armada-47bfca1';
 EXCEPTION WHEN check_violation THEN denied := true;
 END;
 ASSERT denied, 'A cost cannot disagree with its raw source value';

 INSERT INTO game_engine.catalogue_unit_cost
 SELECT 'mismatched-revision', game_name, faction_name, classification, unit_name, catalogue_file, 'different',
        points_cost, unique_key, jsonb_set(provenance, '{revision}', '"different"') FROM game_engine.catalogue_unit_cost WHERE cost_set='bsdata-armada-47bfca1';
 snapshot := game_engine.fn_rules_catalogue_priced_source(version, names, 'mismatched-revision')::jsonb;
 ASSERT snapshot #> '{units,0,pointsCost}' = 'null'::jsonb, 'Names alone cannot match a different source revision';
 ASSERT snapshot #> '{units,1,pointsCost}' = 'null'::jsonb;
 ASSERT game_engine.fn_rules_catalogue_priced_source(-1, names, 'bsdata-armada-47bfca1') IS NULL;
END
$test$;
ROLLBACK;
