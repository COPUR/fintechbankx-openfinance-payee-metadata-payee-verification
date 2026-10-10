-- Append-only change history of the imported payee directory (additive; V1
-- unchanged). One row per inserted or updated payee_directory_entry row: the
-- login role (session_user), application_name and time, plus old and new values.
--
-- Data minimisation: holder names are NOT copied. The history keeps a SHA-256 of
-- the old and new holder_name, which shows that and when a name changed without
-- keeping a name the directory no longer holds. Like the main table, the
-- identification and the name digests are classified (column comments).
--
-- The trigger function is SECURITY DEFINER (owned by the schema owner), so the
-- import role writes history without any privilege on the history table. Inside
-- such a function current_user is the owner, so the login role comes from session_user.

CREATE TABLE payee_directory_entry_history (
    history_id               BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id                 BIGINT        NOT NULL,
    operation                VARCHAR(6)    NOT NULL,
    scheme_name              VARCHAR(32)   NOT NULL,
    identification           VARCHAR(64)   NOT NULL,
    old_holder_name_sha256   CHAR(64),
    new_holder_name_sha256   CHAR(64)      NOT NULL,
    old_account_type         VARCHAR(16),
    new_account_type         VARCHAR(16)   NOT NULL,
    old_account_status       VARCHAR(16),
    new_account_status       VARCHAR(16)   NOT NULL,
    old_updated_at           TIMESTAMPTZ,
    new_updated_at           TIMESTAMPTZ   NOT NULL,
    changed_by               TEXT          NOT NULL,
    application_name         TEXT          NOT NULL,
    changed_at               TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_payee_directory_history_operation CHECK (operation IN ('INSERT', 'UPDATE')),
    CONSTRAINT ck_payee_directory_history_old CHECK ((operation = 'INSERT') = (old_updated_at IS NULL))
);

CREATE INDEX ix_payee_directory_history_entry ON payee_directory_entry_history (entry_id, changed_at);

CREATE FUNCTION payee_directory_record_history() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
BEGIN
    EXECUTE format('INSERT INTO %I.payee_directory_entry_history (entry_id, operation, scheme_name, identification,'
                   ' old_holder_name_sha256, new_holder_name_sha256, old_account_type, new_account_type,'
                   ' old_account_status, new_account_status, old_updated_at, new_updated_at,'
                   ' changed_by, application_name, changed_at)'
                   ' VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15)', TG_TABLE_SCHEMA)
        USING NEW.entry_id, TG_OP, NEW.scheme_name, NEW.identification,
              CASE WHEN TG_OP = 'UPDATE' THEN encode(sha256(convert_to(OLD.holder_name, 'UTF8')), 'hex') END,
              encode(sha256(convert_to(NEW.holder_name, 'UTF8')), 'hex'),
              CASE WHEN TG_OP = 'UPDATE' THEN OLD.account_type END, NEW.account_type,
              CASE WHEN TG_OP = 'UPDATE' THEN OLD.account_status END, NEW.account_status,
              CASE WHEN TG_OP = 'UPDATE' THEN OLD.updated_at END, NEW.updated_at,
              session_user::text, coalesce(nullif(current_setting('application_name', true), ''), 'unknown'),
              clock_timestamp();
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_payee_directory_history
    AFTER INSERT OR UPDATE ON payee_directory_entry
    FOR EACH ROW EXECUTE FUNCTION payee_directory_record_history();

CREATE FUNCTION payee_directory_history_reject_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'payee_directory_entry_history is append-only';
END;
$$;

CREATE TRIGGER trg_payee_directory_history_append_only
    BEFORE UPDATE OR DELETE ON payee_directory_entry_history
    FOR EACH ROW EXECUTE FUNCTION payee_directory_history_reject_change();

CREATE TRIGGER trg_payee_directory_history_no_truncate
    BEFORE TRUNCATE ON payee_directory_entry_history
    FOR EACH STATEMENT EXECUTE FUNCTION payee_directory_history_reject_change();

REVOKE ALL ON FUNCTION payee_directory_record_history() FROM PUBLIC;
REVOKE ALL ON FUNCTION payee_directory_history_reject_change() FROM PUBLIC;

COMMENT ON TABLE payee_directory_entry_history IS
    'Append-only audit trail of payee_directory_entry inserts and updates (who: session_user, via: application_name, when). Holds no holder names, only SHA-256 digests. Classification: confidential-personal.';
COMMENT ON COLUMN payee_directory_entry_history.identification IS
    'Account identification as in payee_directory_entry. Classification: confidential.';
COMMENT ON COLUMN payee_directory_entry_history.old_holder_name_sha256 IS
    'PII (pseudonymous): SHA-256 of the previous holder_name, never the name. Classification: confidential-personal. Never log, publish or copy.';
COMMENT ON COLUMN payee_directory_entry_history.new_holder_name_sha256 IS
    'PII (pseudonymous): SHA-256 of the new holder_name, never the name. Classification: confidential-personal. Never log, publish or copy.';
