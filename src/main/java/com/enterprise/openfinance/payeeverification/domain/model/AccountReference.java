package com.enterprise.openfinance.payeeverification.domain.model;

import com.enterprise.openfinance.payeeverification.domain.exception.InvalidAccountReferenceException;
import com.enterprise.openfinance.payeeverification.domain.service.IbanValidator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Payee account identifier (scheme + identification). The raw
 * identification never leaves the service in events or logs; {@link #hash()}
 * is the pseudonymous reference used instead.
 */
public record AccountReference(String schemeName, String identification) {

    public static final String IBAN = "IBAN";

    public AccountReference {
        if (schemeName == null || schemeName.isBlank()) {
            throw new InvalidAccountReferenceException("SchemeName is required");
        }
        if (identification == null || identification.isBlank()) {
            throw new InvalidAccountReferenceException("Identification is required");
        }
        schemeName = schemeName.trim().toUpperCase(Locale.ROOT);
        identification = identification.replace(" ", "").trim().toUpperCase(Locale.ROOT);
        if (IBAN.equals(schemeName) && !IbanValidator.isValid(identification)) {
            throw new InvalidAccountReferenceException("Identification is not a valid IBAN");
        }
    }

    public static AccountReference of(String schemeName, String identification) {
        return new AccountReference(schemeName, identification);
    }

    /** Lower-case hex SHA-256 of {@code SCHEME:IDENTIFICATION}. */
    public String hash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((schemeName + ":" + identification).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    @Override
    public String toString() {
        return "AccountReference[" + schemeName + ", ref=" + hash().substring(0, 12) + "]";
    }
}
