-- Permit catalogue content changes only on objects owned by draft revisions.

BEGIN;

CREATE OR REPLACE PROCEDURE game_engine.sp_definition_object_update(
    IN p_definition_id bigint,
    IN p_object_id bigint,
    IN p_object_name text,
    IN p_object_description text,
    IN p_display_text text,
    IN p_object_data text,
    IN p_source_reference text
) LANGUAGE plpgsql AS $$
BEGIN
    UPDATE game_engine.game_object
    SET object_name = p_object_name,
        object_description = p_object_description,
        display_text = p_display_text,
        object_data_json = COALESCE(p_object_data, '{}')::jsonb,
        source_reference_json = COALESCE(p_source_reference, '{}')::jsonb
    WHERE game_object_id = p_object_id
      AND game_version_id = p_definition_id
      AND EXISTS (
          SELECT 1 FROM game_engine.game_version
          WHERE game_version_id = p_definition_id AND lifecycle_status = 'draft'
      );

    IF NOT FOUND THEN
        IF EXISTS (
            SELECT 1 FROM game_engine.game_object
            WHERE game_object_id = p_object_id AND game_version_id = p_definition_id
        ) THEN
            RAISE EXCEPTION 'Only draft definitions can be edited' USING ERRCODE = '55000';
        END IF;
        RAISE EXCEPTION 'Object % does not belong to definition %', p_object_id, p_definition_id
            USING ERRCODE = '23503';
    END IF;
END
$$;

COMMIT;
