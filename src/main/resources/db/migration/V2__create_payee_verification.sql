-- Insert-only record of every Confirmation of Payee decision. Holds no
-- names and no raw account identification: the account is referenced by
-- sha256(scheme:identification). (tpp_id, interaction_id) is the
-- idempotency key: a repeated request returns the stored decision.

CREATE TABLE payee_verification (
    verification_id         UUID          PRIMARY KEY,
    tpp_id                  VARCHAR(128)  NOT NULL,
    interaction_id          VARCHAR(128)  NOT NULL,
    account_reference_hash  VARCHAR(64)   NOT NULL,
    account_status          VARCHAR(16)   NOT NULL,
    match_outcome           VARCHAR(16)   NOT NULL,
    reason_code             VARCHAR(32)   NOT NULL,
    match_score             SMALLINT      NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL,

    CONSTRAINT uq_payee_verification_idempotency UNIQUE (tpp_id, interaction_id),
    CONSTRAINT ck_payee_verification_hash CHECK (account_reference_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_payee_verification_status CHECK (account_status IN ('ACTIVE', 'CLOSED', 'DECEASED', 'UNKNOWN')),
    CONSTRAINT ck_payee_verification_outcome CHECK (match_outcome IN ('MATCH', 'CLOSE_MATCH', 'NO_MATCH', 'UNABLE_TO_CHECK')),
    CONSTRAINT ck_payee_verification_reason CHECK (reason_code IN (
        'EXACT_NAME_MATCH', 'CLOSE_NAME_MATCH', 'NAME_MISMATCH',
        'ACCOUNT_NOT_FOUND', 'ACCOUNT_CLOSED', 'ACCOUNT_DECEASED')),
    CONSTRAINT ck_payee_verification_score CHECK (match_score BETWEEN 0 AND 100)
);

CREATE INDEX ix_payee_verification_account ON payee_verification (account_reference_hash, created_at);
CREATE INDEX ix_payee_verification_created_at ON payee_verification (created_at);

-- Decisions are evidence: they are never changed after insert. Retention
-- deletes are allowed (see docs/migration runbook).
CREATE FUNCTION payee_verification_reject_update() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'payee_verification is insert-only';
END;
$$;

CREATE TRIGGER trg_payee_verification_insert_only
    BEFORE UPDATE ON payee_verification
    FOR EACH ROW EXECUTE FUNCTION payee_verification_reject_update();

COMMENT ON TABLE payee_verification IS
    'Insert-only Confirmation of Payee decisions. No personal names; account referenced by hash only.';
COMMENT ON COLUMN payee_verification.account_reference_hash IS
    'Lower-case hex sha256 of SCHEME:IDENTIFICATION. Pseudonymous, not anonymous: treat as confidential.';
