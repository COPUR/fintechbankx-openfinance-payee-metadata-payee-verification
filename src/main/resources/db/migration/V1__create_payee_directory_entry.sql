-- Payee directory: the account-holder names the matcher compares against.
-- A projection of core-banking account data (see
-- docs/architecture/decisions/ADR-0001-payee-directory-projection.md). The
-- service only reads it; db/import/import-payee-directory.sh loads it.

CREATE TABLE payee_directory_entry (
    entry_id        BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scheme_name     VARCHAR(32)   NOT NULL,
    identification  VARCHAR(64)   NOT NULL,
    holder_name     VARCHAR(140)  NOT NULL,
    account_type    VARCHAR(16)   NOT NULL,
    account_status  VARCHAR(16)   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,

    CONSTRAINT uq_payee_directory_account UNIQUE (scheme_name, identification),
    CONSTRAINT ck_payee_directory_scheme CHECK (scheme_name = upper(scheme_name)),
    CONSTRAINT ck_payee_directory_identification CHECK (identification = upper(identification) AND position(' ' in identification) = 0),
    CONSTRAINT ck_payee_directory_account_type CHECK (account_type IN ('PERSONAL', 'BUSINESS')),
    CONSTRAINT ck_payee_directory_account_status CHECK (account_status IN ('ACTIVE', 'CLOSED', 'DECEASED'))
);

COMMENT ON TABLE payee_directory_entry IS
    'Projection of core-banking accounts for Confirmation of Payee. Owner of the source data: core banking (no fintechbankx accounts service yet).';
COMMENT ON COLUMN payee_directory_entry.holder_name IS
    'PII: account holder name. Classification: confidential-personal. Never log, publish in events or copy to other stores.';
COMMENT ON COLUMN payee_directory_entry.identification IS
    'Account identification within scheme_name, normalised upper case without spaces. Classification: confidential.';
COMMENT ON COLUMN payee_directory_entry.updated_at IS
    'When the source system last changed the account; the import only overwrites a row with data at least this recent.';
