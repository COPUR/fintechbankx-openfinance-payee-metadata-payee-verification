package com.enterprise.openfinance.payeeverification.domain.service;

import com.enterprise.openfinance.payeeverification.domain.model.MatchOutcome;

import java.util.Locale;

/**
 * Compares the name typed by the payer with the account holder name.
 * Names are lower-cased, punctuation becomes a space and runs of spaces
 * collapse; letters of any script (Arabic included) are kept. The score is
 * the normalised Levenshtein similarity in percent: 100 is a MATCH, at or
 * above the threshold a CLOSE_MATCH, below it a NO_MATCH.
 */
public final class PayeeNameMatcher {

    public static final int DEFAULT_CLOSE_MATCH_THRESHOLD = 85;

    private final int closeMatchThreshold;

    public PayeeNameMatcher(int closeMatchThreshold) {
        if (closeMatchThreshold < 1 || closeMatchThreshold > 99) {
            throw new IllegalArgumentException("closeMatchThreshold must be between 1 and 99");
        }
        this.closeMatchThreshold = closeMatchThreshold;
    }

    public NameMatch match(String registeredName, String requestedName) {
        String registered = normalize(registeredName);
        String requested = normalize(requestedName);
        if (registered.isEmpty() || requested.isEmpty()) {
            return new NameMatch(0, MatchOutcome.NO_MATCH);
        }
        int score = score(registered, requested);
        if (score == 100) {
            return new NameMatch(score, MatchOutcome.MATCH);
        }
        if (score >= closeMatchThreshold) {
            return new NameMatch(score, MatchOutcome.CLOSE_MATCH);
        }
        return new NameMatch(score, MatchOutcome.NO_MATCH);
    }

    static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static int score(String left, String right) {
        int distance = levenshtein(left, right);
        int maxLength = Math.max(left.length(), right.length());
        double similarity = 1.0d - ((double) distance / maxLength);
        return (int) Math.max(0, Math.min(100, Math.round(similarity * 100)));
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
