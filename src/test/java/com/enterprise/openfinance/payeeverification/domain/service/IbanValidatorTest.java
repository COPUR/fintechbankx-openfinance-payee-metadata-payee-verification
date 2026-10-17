package com.enterprise.openfinance.payeeverification.domain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IbanValidatorTest {

    @Test
    void acceptsWellFormedIbansWithACorrectMod97Checksum() {
        assertThat(IbanValidator.isValid("AE070331234567890123456")).isTrue();
        assertThat(IbanValidator.isValid("GB82WEST12345698765432")).isTrue();
        assertThat(IbanValidator.isValid("gb82 west 1234 5698 7654 32")).isTrue();
    }

    @Test
    void rejectsAWrongCheckDigit() {
        assertThat(IbanValidator.isValid("AE080331234567890123456")).isFalse();
        // The sample used by the March 2026 seed adapter fails the checksum.
        assertThat(IbanValidator.isValid("AE29000000123456789")).isFalse();
    }

    @Test
    void rejectsInvalidCharactersAndLengths() {
        assertThat(IbanValidator.isValid("GB82WEST1234@698765432")).isFalse();
        assertThat(IbanValidator.isValid("GB82")).isFalse();
        assertThat(IbanValidator.isValid("GB82WEST123456987654321234567890123")).isFalse();
        assertThat(IbanValidator.isValid(null)).isFalse();
        assertThat(IbanValidator.isValid("  ")).isFalse();
    }
}
