package com.enterprise.openfinance.payeeverification.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcDpopReplayStoreTest {

    private static final Instant AT = Instant.parse("2026-10-08T09:31:05Z");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcDpopReplayStore store = new JdbcDpopReplayStore(jdbc);

    @Test
    void firstUseInsertsAndASecondUseFindsTheRow() {
        when(jdbc.update(anyString(), eq("key-1"), eq(Timestamp.from(AT)))).thenReturn(1, 0);

        assertThat(store.markUsed("key-1", AT)).isTrue();
        assertThat(store.markUsed("key-1", AT)).isFalse();
    }

    @Test
    void purgeDeletesExpiredProofs() {
        when(jdbc.update(anyString(), eq(Timestamp.from(AT)))).thenReturn(4);

        assertThat(store.purgeExpired(AT)).isEqualTo(4);
    }
}
