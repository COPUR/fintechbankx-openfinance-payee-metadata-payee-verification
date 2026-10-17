package com.enterprise.openfinance.payeeverification.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataPayeeVerificationRepository extends JpaRepository<PayeeVerificationJpaEntity, UUID> {

    Optional<PayeeVerificationJpaEntity> findByTppIdAndInteractionId(String tppId, String interactionId);
}
