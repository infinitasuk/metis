-- Establish the lifecycle for catalogue definition revisions.
-- Static catalogue rows are still game-scoped; revision-scoped row copies are
-- the next step before editing a draft can change catalogue content safely.

BEGIN;

ALTER TABLE game_engine.game_version
    ADD COLUMN IF NOT EXISTS lifecycle_status text NOT NULL DEFAULT 'draft',
    ADD COLUMN IF NOT EXISTS published_at timestamptz,
    ADD COLUMN IF NOT EXISTS retired_at timestamptz,
    ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE game_engine.game_version
    DROP CONSTRAINT IF EXISTS game_version_lifecycle_status_ck;
ALTER TABLE game_engine.game_version
    ADD CONSTRAINT game_version_lifecycle_status_ck
    CHECK (lifecycle_status IN ('draft', 'published', 'retired'));

UPDATE game_engine.game_version
SET lifecycle_status = 'published',
    published_at = COALESCE(published_at, now()),
    updated_at = now()
WHERE is_active AND lifecycle_status = 'draft';

UPDATE game_engine.game_version
SET lifecycle_status = 'retired',
    retired_at = COALESCE(retired_at, now()),
    updated_at = now()
WHERE NOT is_active AND lifecycle_status = 'draft';

CREATE UNIQUE INDEX IF NOT EXISTS game_version_one_published_uq
    ON game_engine.game_version (game_id)
    WHERE lifecycle_status = 'published';

CREATE OR REPLACE FUNCTION game_engine.fn_definition_version_validate(p_definition_id bigint)
RETURNS TABLE (is_valid boolean, errors jsonb)
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_game_id bigint;
    v_errors jsonb := '[]'::jsonb;
BEGIN
    SELECT game_id INTO v_game_id
    FROM game_engine.game_version
    WHERE game_version_id = p_definition_id;

    IF NOT FOUND THEN
        RETURN QUERY SELECT false, jsonb_build_array(jsonb_build_object(
            'code', 'DEFINITION_NOT_FOUND', 'message', 'Definition revision does not exist'));
        RETURN;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM game_engine.game_version
        WHERE game_version_id = p_definition_id
          AND nullif(trim(version_code), '') IS NOT NULL
    ) THEN
        v_errors := v_errors || jsonb_build_array(jsonb_build_object(
            'code', 'VERSION_CODE_REQUIRED', 'message', 'A version code is required'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM game_engine.game_version
        WHERE game_version_id = p_definition_id
          AND nullif(trim(version_name), '') IS NOT NULL
    ) THEN
        v_errors := v_errors || jsonb_build_array(jsonb_build_object(
            'code', 'VERSION_NAME_REQUIRED', 'message', 'A version name is required'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM game_engine.faction WHERE game_id = v_game_id) THEN
        v_errors := v_errors || jsonb_build_array(jsonb_build_object(
            'code', 'FACTIONS_REQUIRED', 'message', 'At least one faction is required'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM game_engine.game_mode WHERE game_id = v_game_id) THEN
        v_errors := v_errors || jsonb_build_array(jsonb_build_object(
            'code', 'GAME_MODE_REQUIRED', 'message', 'At least one game mode is required'));
    END IF;

    RETURN QUERY SELECT jsonb_array_length(v_errors) = 0, v_errors;
END
$$;

CREATE OR REPLACE FUNCTION game_engine.fn_definition_version_list(p_game_id bigint)
RETURNS TABLE (
    game_version_id bigint,
    game_id bigint,
    version_code text,
    version_name text,
    lifecycle_status text,
    release_date date,
    source_revision text,
    source_name text,
    source_url text,
    published_at timestamptz,
    created_at timestamptz,
    updated_at timestamptz,
    is_valid boolean,
    validation_errors jsonb
) LANGUAGE sql STABLE AS $$
    SELECT gv.game_version_id, gv.game_id, gv.version_code, gv.version_name,
           gv.lifecycle_status, gv.release_date, gv.source_revision,
           cs.source_name, cs.source_url, gv.published_at, gv.created_at,
           gv.updated_at, validation.is_valid, validation.errors
    FROM game_engine.game_version gv
    LEFT JOIN game_engine.content_source cs ON cs.content_source_id = gv.content_source_id
    CROSS JOIN LATERAL game_engine.fn_definition_version_validate(gv.game_version_id) validation
    WHERE p_game_id IS NULL OR gv.game_id = p_game_id
    ORDER BY gv.game_id, gv.release_date DESC NULLS LAST, gv.game_version_id DESC
$$;

CREATE OR REPLACE PROCEDURE game_engine.sp_definition_version_create(
    IN p_game_id bigint,
    IN p_version_code text,
    IN p_version_name text,
    IN p_source_revision text,
    INOUT p_definition_id bigint
) LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM game_engine.game WHERE game_id = p_game_id) THEN
        RAISE EXCEPTION 'Game % does not exist', p_game_id USING ERRCODE = '23503';
    END IF;
    INSERT INTO game_engine.game_version (
        game_id, version_code, version_name, source_revision, lifecycle_status, is_active
    ) VALUES (
        p_game_id, p_version_code, p_version_name, p_source_revision, 'draft', false
    ) RETURNING game_version_id INTO p_definition_id;
END
$$;

CREATE OR REPLACE PROCEDURE game_engine.sp_definition_version_update(
    IN p_definition_id bigint,
    IN p_version_code text,
    IN p_version_name text,
    IN p_source_revision text
) LANGUAGE plpgsql AS $$
BEGIN
    UPDATE game_engine.game_version
    SET version_code = p_version_code,
        version_name = p_version_name,
        source_revision = p_source_revision,
        updated_at = now()
    WHERE game_version_id = p_definition_id AND lifecycle_status = 'draft';
    IF NOT FOUND THEN
        IF EXISTS (SELECT 1 FROM game_engine.game_version WHERE game_version_id = p_definition_id) THEN
            RAISE EXCEPTION 'Only draft definitions can be edited' USING ERRCODE = '55000';
        END IF;
        RAISE EXCEPTION 'Definition % does not exist', p_definition_id USING ERRCODE = 'P0002';
    END IF;
END
$$;

CREATE OR REPLACE PROCEDURE game_engine.sp_definition_version_publish(IN p_definition_id bigint)
LANGUAGE plpgsql AS $$
DECLARE
    v_game_id bigint;
    v_valid boolean;
    v_errors jsonb;
BEGIN
    SELECT game_id INTO v_game_id
    FROM game_engine.game_version
    WHERE game_version_id = p_definition_id AND lifecycle_status = 'draft';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Only draft definitions can be published' USING ERRCODE = '55000';
    END IF;

    SELECT is_valid, errors INTO v_valid, v_errors
    FROM game_engine.fn_definition_version_validate(p_definition_id);
    IF NOT v_valid THEN
        RAISE EXCEPTION 'Definition % failed validation: %', p_definition_id, v_errors::text USING ERRCODE = '23514';
    END IF;

    UPDATE game_engine.game_version
    SET lifecycle_status = 'retired', is_active = false,
        retired_at = COALESCE(retired_at, now()), updated_at = now()
    WHERE game_id = v_game_id AND lifecycle_status = 'published';

    UPDATE game_engine.game_version
    SET lifecycle_status = 'published', is_active = true,
        published_at = now(), retired_at = null, updated_at = now()
    WHERE game_version_id = p_definition_id;
END
$$;

CREATE OR REPLACE PROCEDURE game_engine.sp_definition_version_retire(IN p_definition_id bigint)
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE game_engine.game_version
    SET lifecycle_status = 'retired', is_active = false,
        retired_at = COALESCE(retired_at, now()), updated_at = now()
    WHERE game_version_id = p_definition_id
      AND lifecycle_status IN ('draft', 'published');
    IF NOT FOUND THEN
        IF EXISTS (SELECT 1 FROM game_engine.game_version WHERE game_version_id = p_definition_id) THEN
            RAISE EXCEPTION 'Definition % is already retired', p_definition_id USING ERRCODE = '55000';
        END IF;
        RAISE EXCEPTION 'Definition % does not exist', p_definition_id USING ERRCODE = 'P0002';
    END IF;
END
$$;

COMMIT;
