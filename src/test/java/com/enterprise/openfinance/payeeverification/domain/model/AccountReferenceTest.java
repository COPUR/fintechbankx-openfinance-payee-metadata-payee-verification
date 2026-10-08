package com.enterprise.openfinance.payeeverification.domain.model;

import com.enterprise.openfinance.payeeverification.domain.exception.InvalidAccountReferenceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountReferenceTest {

    @Test
    void normalisesSchemeAndIdentification() {
        AccountReference reference = AccountReference.of(" iban ", "ae07 0331 2345 6789 0123 456");

        assertThat(reference.schemeName()).isEqualTo("IBAN");
        assertThat(reference.identification()).isEqualTo("AE070331234567890123456");
    }

    @Test
    void hashIsTheSha256HexOfSchemeColonIdentification() {
        // printf 'IBAN:AE070331234567890123456' | sha256sum
        assertThat(AccountReference.of("IBAN", "AE070331234567890123456").hash())
            .isEqualTo("b538b8430ce7c538678b8dd357195baa7651210e6a4462655e5dd941afefcd15");
        assertThat(AccountReference.of("iban", "AE07 0331 2345 6789 0123 456").hash())
            .isEqualTo(AccountReference.of("IBAN", "AE070331234567890123456").hash());
    }

    @Test
    void rejectsAnIbanWithABadChecksum() {
        assertThatThrownBy(() -> AccountReference.of("IBAN", "AE080331234567890123456"))
            .isInstanceOf(InvalidAccountReferenceException.class)
            .hasMessage("Identification is not a valid IBAN");
    }

    @Test
    void otherSchemesAreNotChecksumValidated() {
        assertThat(AccountReference.of("BBAN", "0331234567890123456").identification()).isEqualTo("0331234567890123456");
    }

    @Test
    void rejectsBlankParts() {
        assertThatThrownBy(() -> AccountReference.of(" ", "AE070331234567890123456"))
            .isInstanceOf(InvalidAccountReferenceException.class);
        assertThatThrownBy(() -> AccountReference.of("IBAN", null))
            .isInstanceOf(InvalidAccountReferenceException.class);
    }

    @Test
    void toStringNeverPrintsTheIdentification() {
        assertThat(AccountReference.of("IBAN", "AE070331234567890123456").toString())
            .doesNotContain("AE070331234567890123456");
    }
}
