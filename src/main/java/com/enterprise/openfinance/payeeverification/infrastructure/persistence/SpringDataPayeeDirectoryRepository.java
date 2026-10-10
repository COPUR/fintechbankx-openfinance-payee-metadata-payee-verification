package com.enterprise.openfinance.payeeverification.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataPayeeDirectoryRepository extends JpaRepository<PayeeDirectoryEntryJpaEntity, Long> {

    Optional<PayeeDirectoryEntryJpaEntity> findBySchemeNameAndIdentification(String schemeName, String identification);
}
