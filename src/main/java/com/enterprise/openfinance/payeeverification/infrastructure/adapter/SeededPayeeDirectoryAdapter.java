package com.enterprise.openfinance.payeeverification.infrastructure.adapter;

import com.enterprise.openfinance.payeeverification.application.port.out.PayeeDirectoryPort;
import com.enterprise.openfinance.payeeverification.domain.AccountStatus;
import com.enterprise.openfinance.payeeverification.domain.PayeeProfile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
public class SeededPayeeDirectoryAdapter implements PayeeDirectoryPort {

    private final Map<String, PayeeProfile> profiles = Map.of(
            "AE29000000123456789", new PayeeProfile("AE29000000123456789", "Al Tareq Trading LLC", AccountStatus.ACTIVE),
            "AE12000000987654321", new PayeeProfile("AE12000000987654321", "Atlas Services LLC", AccountStatus.ACTIVE),
            "AE99000000111111111", new PayeeProfile("AE99000000111111111", "Dormant Company LLC", AccountStatus.CLOSED)
    );

    @Override
    public Optional<PayeeProfile> findByIdentification(String identification) {
        return Optional.ofNullable(profiles.get(identification));
    }
}
