-- DBA bootstrap for svc-of-payee-verification, once per environment, run with
-- the RDS-managed admin credential (Terraform output master_user_secret_arn)
-- while connected to db_of_payee_verification_<env>:
--
--   psql "host=<writer> dbname=db_of_payee_verification_<env> user=<admin> sslmode=require" \
--        -v ON_ERROR_STOP=1 -f db/bootstrap/bootstrap-roles.sql
--
-- Creates three LOGIN roles without passwords; the DBA then sets each password
-- (psql \password <role>) and stores {"username","password"} in Secrets Manager:
--
--   role                        secret                                          used by
--   payee_verification_migrate  <env>/payee-verification-service/db-migration   Flyway only (Helm init container); owns the schema
--   payee_verification_app      <env>/payee-verification-service/db-app         the service pods; DML the use cases need, owns nothing
--   payee_verification_import   <env>/payee-verification-service/db-import      db/import/import-payee-directory.sh
--
-- Table privileges are granted by Flyway (V6__grant_least_privilege.sql), which
-- runs as the schema owner. Run this script before the first deploy; it is
-- idempotent and may be re-run.

DO $$
DECLARE
    r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['payee_verification_migrate', 'payee_verification_app', 'payee_verification_import'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE', r);
        END IF;
    END LOOP;
END
$$;

DO $$
BEGIN
    EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO payee_verification_migrate, payee_verification_app, payee_verification_import',
                   current_database());
    -- import-payee-directory.sh stages the CSV in temporary tables.
    EXECUTE format('GRANT TEMPORARY ON DATABASE %I TO payee_verification_import', current_database());
    -- Flyway (create-schemas) creates sc_of_payee_verification, so the migrate role owns it.
    EXECUTE format('GRANT CREATE ON DATABASE %I TO payee_verification_migrate', current_database());
END
$$;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
