-- DPoP proof replay protection (RFC 9449 section 11.1) shared by all
-- replicas: a proof's jti may be used once while its iat window lasts.

CREATE TABLE dpop_proof_replay (
    proof_key   VARCHAR(64)  PRIMARY KEY,
    expires_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_dpop_proof_replay_expires_at ON dpop_proof_replay (expires_at);

COMMENT ON TABLE dpop_proof_replay IS 'sha256(jkt:jti) of DPoP proofs already accepted; rows are purged after expires_at.';
