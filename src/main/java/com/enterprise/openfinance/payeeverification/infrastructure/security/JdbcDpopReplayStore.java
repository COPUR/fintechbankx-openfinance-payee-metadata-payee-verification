package com.enterprise.openfinance.payeeverification.infrastructure.security;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/** Replay store in sc_of_payee_verification.dpop_proof_replay, shared by all replicas. */
public class JdbcDpopReplayStore implements DpopReplayStore {

    private final JdbcTemplate jdbc;

    public JdbcDpopReplayStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean markUsed(String proofKey, Instant expiresAt) {
        int inserted = jdbc.update(
            "insert into dpop_proof_replay (proof_key, expires_at) values (?, ?) on conflict (proof_key) do nothing",
            proofKey, Timestamp.from(expiresAt));
        return inserted == 1;
    }

    @Override
    public int purgeExpired(Instant now) {
        return jdbc.update("delete from dpop_proof_replay where expires_at < ?", Timestamp.from(now));
    }
}
