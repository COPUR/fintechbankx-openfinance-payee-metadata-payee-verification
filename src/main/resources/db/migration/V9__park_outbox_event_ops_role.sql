-- Operator parking without the schema-owner credential (additive; V7 unchanged).
-- park_outbox_event() (V7) becomes SECURITY DEFINER: it runs with the rights of
-- its owner, the schema owner payee_verification_migrate, with search_path
-- pinned to this schema and pg_temp. EXECUTE is granted only to
-- payee_verification_ops (db/bootstrap/bootstrap-roles.sql). Besides USAGE on the
-- schema it may only read the columns needed to find rows to park (ids, topic,
-- timestamps, park state, and the TPP of a decision for the go-live parity
-- step); never payloads or decision details, and it cannot write any table. parked_by keeps recording session_user, the
-- operator's login (payee_verification_ops), not the owner.
-- A role that does not exist (local and CI databases) is skipped with a notice;
-- if the ops role is created after this migration ran, re-run the DO block as
-- the owner.

DO $$
DECLARE
    s text := current_schema();
BEGIN
    EXECUTE format('ALTER FUNCTION %I.park_outbox_event(UUID, TEXT) SECURITY DEFINER', s);
    EXECUTE format('ALTER FUNCTION %I.park_outbox_event(UUID, TEXT) SET search_path = %I, pg_temp', s, s);
    EXECUTE format('REVOKE ALL ON FUNCTION %I.park_outbox_event(UUID, TEXT) FROM PUBLIC', s);
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payee_verification_ops') THEN
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO payee_verification_ops', s);
        EXECUTE format('GRANT EXECUTE ON FUNCTION %I.park_outbox_event(UUID, TEXT) TO payee_verification_ops', s);
        EXECUTE format('GRANT SELECT (event_id, created_seq, aggregate_id, topic, created_at, published_at, attempts, '
                       'parked_at, parked_reason, parked_by, park_counted) ON %I.outbox_event TO payee_verification_ops', s);
        EXECUTE format('GRANT SELECT (verification_id, tpp_id) ON %I.payee_verification TO payee_verification_ops', s);
    ELSE
        RAISE NOTICE 'role payee_verification_ops does not exist; EXECUTE grant skipped';
    END IF;
END
$$;
