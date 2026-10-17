package com.enterprise.openfinance.payeeverification.domain.service;

import com.enterprise.openfinance.payeeverification.domain.model.MatchOutcome;

/** Similarity score (0..100) and the outcome it maps to. */
public record NameMatch(int score, MatchOutcome outcome) {
    public NameMatch {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
    }
}
