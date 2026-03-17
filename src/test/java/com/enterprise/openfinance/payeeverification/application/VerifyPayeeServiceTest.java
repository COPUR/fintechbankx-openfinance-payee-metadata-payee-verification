package com.enterprise.openfinance.payeeverification.application;

import com.enterprise.openfinance.payeeverification.application.port.out.PayeeDirectoryPort;
import com.enterprise.openfinance.payeeverification.domain.AccountStatus;
import com.enterprise.openfinance.payeeverification.domain.MatchOutcome;
import com.enterprise.openfinance.payeeverification.domain.PayeeProfile;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VerifyPayeeServiceTest {

    @Test
    void shouldReturnUnableToCheckWhenAccountNotActive() {
        PayeeDirectoryPort directoryPort = Mockito.mock(PayeeDirectoryPort.class);
        Mockito.when(directoryPort.findByIdentification("AE100")).thenReturn(
                Optional.of(new PayeeProfile("AE100", "Alpha Legal Name", AccountStatus.CLOSED))
        );

        VerifyPayeeService service = new VerifyPayeeService(directoryPort);
        VerifyPayeeResult result = service.verify(new VerifyPayeeCommand("AE100", "IBAN", "Alpha"));

        assertThat(result.accountStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(result.matchOutcome()).isEqualTo(MatchOutcome.UNABLE_TO_CHECK);
        assertThat(result.matchedName()).isNull();
    }

    @Test
    void shouldReturnNoMatchWhenProfileNotFound() {
        PayeeDirectoryPort directoryPort = Mockito.mock(PayeeDirectoryPort.class);
        Mockito.when(directoryPort.findByIdentification("UNKNOWN")).thenReturn(Optional.empty());

        VerifyPayeeService service = new VerifyPayeeService(directoryPort);
        VerifyPayeeResult result = service.verify(new VerifyPayeeCommand("UNKNOWN", "IBAN", "Any"));

        assertThat(result.accountStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(result.matchOutcome()).isEqualTo(MatchOutcome.UNABLE_TO_CHECK);
        assertThat(result.matchedName()).isNull();
    }
}
