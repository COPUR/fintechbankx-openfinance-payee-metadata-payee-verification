package com.enterprise.openfinance.payeeverification.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PayeeNameMatcherTest {

    private final PayeeNameMatcher matcher = new PayeeNameMatcher();

    @Test
    void shouldReturnMatchWhenNamesAreEquivalent() {
        MatchOutcome outcome = matcher.match("Al Tareq Trading LLC", "al   tareq trading llc");
        assertThat(outcome).isEqualTo(MatchOutcome.MATCH);
    }

    @Test
    void shouldReturnCloseMatchWhenNamesAreNear() {
        MatchOutcome outcome = matcher.match("Al Tareq Trading LLC", "Al Tariq Trading LLC");
        assertThat(outcome).isEqualTo(MatchOutcome.CLOSE_MATCH);
    }

    @Test
    void shouldReturnNoMatchForDifferentNames() {
        MatchOutcome outcome = matcher.match("Al Tareq Trading LLC", "Random Corporation");
        assertThat(outcome).isEqualTo(MatchOutcome.NO_MATCH);
    }
}
