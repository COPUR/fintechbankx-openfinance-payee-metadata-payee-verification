package com.enterprise.openfinance.payeeverification.domain.service;

import com.enterprise.openfinance.payeeverification.domain.model.MatchOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayeeNameMatcherTest {

    private final PayeeNameMatcher matcher = new PayeeNameMatcher(85);

    @Test
    void equivalentNamesIgnoringCaseSpacingAndPunctuationAreAMatchWithScore100() {
        NameMatch match = matcher.match("Al Tareq Trading LLC", "al   tareq trading, llc.");

        assertThat(match.outcome()).isEqualTo(MatchOutcome.MATCH);
        assertThat(match.score()).isEqualTo(100);
    }

    @Test
    void oneLetterOffInTwentyCharactersScores95AndIsACloseMatch() {
        NameMatch match = matcher.match("Al Tareq Trading LLC", "Al Tariq Trading LLC");

        assertThat(match.score()).isEqualTo(95);
        assertThat(match.outcome()).isEqualTo(MatchOutcome.CLOSE_MATCH);
    }

    @Test
    void scoreExactlyAtTheThresholdIsACloseMatchAndOneBelowIsNoMatch() {
        // "abcdefghijklmnopqrst" vs three substitutions: 1 - 3/20 = 0.85 -> 85
        assertThat(matcher.match("abcdefghijklmnopqrst", "xyzdefghijklmnopqrst").score()).isEqualTo(85);
        assertThat(matcher.match("abcdefghijklmnopqrst", "xyzdefghijklmnopqrst").outcome()).isEqualTo(MatchOutcome.CLOSE_MATCH);
        // four substitutions: 1 - 4/20 = 0.80 -> 80
        assertThat(matcher.match("abcdefghijklmnopqrst", "xyzwefghijklmnopqrst").outcome()).isEqualTo(MatchOutcome.NO_MATCH);
    }

    @Test
    void differentNamesAreNoMatch() {
        NameMatch match = matcher.match("Al Tareq Trading LLC", "Random Corporation");

        assertThat(match.outcome()).isEqualTo(MatchOutcome.NO_MATCH);
        assertThat(match.score()).isLessThan(85);
    }

    @Test
    void arabicNamesAreComparedRatherThanStrippedAway() {
        assertThat(matcher.match("شركة الطارق للتجارة", "شركة  الطارق للتجارة").outcome()).isEqualTo(MatchOutcome.MATCH);
        assertThat(matcher.match("شركة الطارق للتجارة", "مؤسسة النور").outcome()).isEqualTo(MatchOutcome.NO_MATCH);
    }

    @Test
    void aRequestedNameWithoutLettersOrDigitsNeverMatches() {
        assertThat(matcher.match("Al Tareq Trading LLC", "!!! ---").outcome()).isEqualTo(MatchOutcome.NO_MATCH);
        assertThat(matcher.match("Al Tareq Trading LLC", null).score()).isZero();
    }

    @Test
    void thresholdMustLeaveRoomForBothCloseMatchAndNoMatch() {
        assertThatThrownBy(() -> new PayeeNameMatcher(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayeeNameMatcher(100)).isInstanceOf(IllegalArgumentException.class);
    }
}
