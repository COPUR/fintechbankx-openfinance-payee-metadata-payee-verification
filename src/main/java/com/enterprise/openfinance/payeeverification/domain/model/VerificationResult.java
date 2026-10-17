package com.enterprise.openfinance.payeeverification.domain.model;

/**
 * What the TPP gets back. {@code matchedName} is the holder name and is only
 * present for a CLOSE_MATCH; {@code replayed} is true when an earlier decision
 * for the same idempotency key was returned.
 */
public record VerificationResult(PayeeVerification verification, String matchedName, boolean replayed) {

    public VerificationResult {
        if (verification == null) {
            throw new IllegalArgumentException("verification is required");
        }
        if (!verification.disclosesHolderName()) {
            matchedName = null;
        }
    }

    @Override
    public String toString() {
        return "VerificationResult[" + verification.verificationId() + ", " + verification.outcome() + ", replayed=" + replayed + "]";
    }
}
