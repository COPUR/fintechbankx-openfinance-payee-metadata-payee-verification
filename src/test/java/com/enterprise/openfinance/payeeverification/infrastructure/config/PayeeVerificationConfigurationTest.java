package com.enterprise.openfinance.payeeverification.infrastructure.config;

import com.enterprise.openfinance.payeeverification.infrastructure.security.DpopReplayStore;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayeeVerificationConfigurationTest {

    private final PayeeVerificationConfiguration configuration = new PayeeVerificationConfiguration();

    @Test
    void sampleSeedIsAddedOnlyWhenEnabled() {
        FluentConfiguration enabled = new FluentConfiguration().locations("classpath:db/migration");
        configuration.payeeDirectorySeed(true).customize(enabled);
        configuration.payeeDirectorySeed(true).customize(enabled);

        FluentConfiguration disabled = new FluentConfiguration().locations("classpath:db/migration");
        configuration.payeeDirectorySeed(false).customize(disabled);

        assertThat(Arrays.stream(enabled.getLocations()).map(Location::toString))
            .containsExactly("classpath:db/migration", "classpath:db/seed");
        assertThat(Arrays.stream(disabled.getLocations()).map(Location::toString))
            .containsExactly("classpath:db/migration");
    }

    @Test
    void matcherUsesTheConfiguredThreshold() {
        assertThat(configuration.payeeNameMatcher(90).match("abcdefghij", "abcdefghiX").outcome().name())
            .isEqualTo("CLOSE_MATCH");
        assertThat(configuration.payeeNameMatcher(95).match("abcdefghij", "abcdefghiX").outcome().name())
            .isEqualTo("NO_MATCH");
    }

    @Test
    void replayPurgeRemovesExpiredProofs() {
        DpopReplayStore store = mock(DpopReplayStore.class);
        Instant now = Instant.parse("2026-10-08T09:30:00Z");
        when(store.purgeExpired(now)).thenReturn(2);

        assertThat(configuration.dpopReplayPurge(store, Clock.fixed(now, ZoneOffset.UTC)).purge()).isEqualTo(2);
    }
}
