-- Test-only fault points, installed solely in the disposable acceptance database.
CREATE SCHEMA rules_test_support;
CREATE TABLE rules_test_support.fault(session_id uuid PRIMARY KEY,kind text NOT NULL);
CREATE FUNCTION rules_test_support.before_transition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE mode text;
BEGIN
 SELECT kind INTO mode FROM rules_test_support.fault WHERE session_id=NEW.session_id;
 IF mode='raise' THEN RAISE EXCEPTION 'PRIVATE_DB_SENTINEL' USING ERRCODE='XX000'; END IF;
 IF mode='wait' THEN PERFORM pg_advisory_xact_lock(771236); END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER rules_test_fault BEFORE INSERT ON game_instance.rules_transition
FOR EACH ROW EXECUTE FUNCTION rules_test_support.before_transition();
