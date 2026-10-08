-- Least-privilege grants for the roles created by db/bootstrap/bootstrap-roles.sql.
-- Runs as the schema owner (payee_verification_migrate); the runtime role owns
-- nothing. Idempotent: if a role was created after this migration ran, re-run
-- this file with psql as the owner. A role that does not exist (local and CI
-- databases) is skipped with a notice.
--
--   payee_verification_app     USAGE on the schema and what the use cases need:
--                              payee_directory_entry  SELECT                    (directory lookup)
--                              payee_verification     SELECT, INSERT            (idempotent decision record)
--                              outbox_event           SELECT, INSERT, UPDATE, DELETE (write, relay, purge)
--                              dpop_proof_replay      SELECT, INSERT, DELETE    (replay check, purge)
--                              No access to payee_directory_entry_history.
--   payee_verification_import  USAGE on the schema; SELECT, INSERT, UPDATE on
--                              payee_directory_entry only (SELECT is needed by
--                              INSERT .. ON CONFLICT DO UPDATE .. WHERE and the
--                              full-mode closure). History is written by the
--                              SECURITY DEFINER trigger.

DO $$
DECLARE
    s text := current_schema();
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payee_verification_app') THEN
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO payee_verification_app', s);
        EXECUTE format('GRANT SELECT ON %I.payee_directory_entry TO payee_verification_app', s);
        EXECUTE format('GRANT SELECT, INSERT ON %I.payee_verification TO payee_verification_app', s);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I.outbox_event TO payee_verification_app', s);
        EXECUTE format('GRANT SELECT, INSERT, DELETE ON %I.dpop_proof_replay TO payee_verification_app', s);
    ELSE
        RAISE NOTICE 'role payee_verification_app does not exist; grants skipped';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payee_verification_import') THEN
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO payee_verification_import', s);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON %I.payee_directory_entry TO payee_verification_import', s);
    ELSE
        RAISE NOTICE 'role payee_verification_import does not exist; grants skipped';
    END IF;
END
$$;
