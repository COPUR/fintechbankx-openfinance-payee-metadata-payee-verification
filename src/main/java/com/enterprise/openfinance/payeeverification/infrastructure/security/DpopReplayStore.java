package com.enterprise.openfinance.payeeverification.infrastructure.security;

import java.time.Instant;

/** Remembers accepted DPoP proofs so each can be used only once. */
public interface DpopReplayStore {

    /** @return true if the key was new and is now recorded; false if it was seen before */
    boolean markUsed(String proofKey, Instant expiresAt);

    int purgeExpired(Instant now);
}
