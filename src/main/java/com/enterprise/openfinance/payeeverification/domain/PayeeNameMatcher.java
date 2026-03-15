package com.enterprise.openfinance.payeeverification.domain;

public class PayeeNameMatcher {

    public MatchOutcome match(String registeredName, String requestedName) {
        String left = normalize(registeredName);
        String right = normalize(requestedName);

        if (left.isBlank() || right.isBlank()) {
            return MatchOutcome.NO_MATCH;
        }
        if (left.equals(right)) {
            return MatchOutcome.MATCH;
        }

        double similarity = similarity(left, right);
        if (similarity >= 0.85d) {
            return MatchOutcome.CLOSE_MATCH;
        }
        return MatchOutcome.NO_MATCH;
    }

    String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private double similarity(String left, String right) {
        int distance = levenshtein(left, right);
        int maxLength = Math.max(left.length(), right.length());
        if (maxLength == 0) {
            return 1.0d;
        }
        return 1.0d - ((double) distance / (double) maxLength);
    }

    private int levenshtein(String left, String right) {
        int[][] dp = new int[left.length() + 1][right.length() + 1];
        for (int i = 0; i <= left.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= right.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[left.length()][right.length()];
    }
}
