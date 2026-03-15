package com.enterprise.openfinance.payeeverification.application.port.out;

import com.enterprise.openfinance.payeeverification.domain.PayeeProfile;

import java.util.Optional;

public interface PayeeDirectoryPort {
    Optional<PayeeProfile> findByIdentification(String identification);
}
