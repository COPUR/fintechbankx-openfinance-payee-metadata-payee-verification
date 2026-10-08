package com.enterprise.openfinance.payeeverification.domain.command;

import com.enterprise.openfinance.payeeverification.domain.model.AccountReference;

/**
 * A TPP asks whether {@code requestedName} matches the holder of
 * {@code account}. {@code tppId} comes from the access token, never from the
 * body; {@code interactionId} (X-FAPI-Interaction-ID) is the idempotency key
 * within that TPP.
 */
public record VerifyPayeeCommand(AccountReference account, String requestedName, String tppId, String interactionId) {

    public VerifyPayeeCommand {
        if (account == null) {
            throw new IllegalArgumentException("account is required");
        }
        if (requestedName == null || requestedName.isBlank()) {
            throw new IllegalArgumentException("requestedName is required");
        }
        if (tppId == null || tppId.isBlank()) {
            throw new IllegalArgumentException("tppId is required");
        }
        if (interactionId == null || interactionId.isBlank()) {
            throw new IllegalArgumentException("interactionId is required");
        }
        tppId = tppId.trim();
        interactionId = interactionId.trim();
    }

    @Override
    public String toString() {
        return "VerifyPayeeCommand[" + account + ", tppId=" + tppId + ", interactionId=" + interactionId + "]";
    }
}
