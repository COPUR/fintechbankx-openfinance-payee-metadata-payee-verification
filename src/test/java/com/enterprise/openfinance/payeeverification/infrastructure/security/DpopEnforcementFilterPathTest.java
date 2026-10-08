package com.enterprise.openfinance.payeeverification.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** The filter selects requests on the decoded path, the one Spring MVC routes on. */
class DpopEnforcementFilterPathTest {

    private final DpopEnforcementFilter filter = new DpopEnforcementFilter(null, null);

    @Test
    void filtersTheTppPathPlainOrPercentEncoded() {
        assertThat(filter.shouldNotFilter(request("/open-finance/v1/confirmation-of-payee/confirmation"))).isFalse();
        assertThat(filter.shouldNotFilter(request("/open-financ%65/v1/confirmation-of-payee/confirmation"))).isFalse();
        assertThat(filter.shouldNotFilter(request("/%6Fpen-finance/v1/confirmation-of-payee/confirmation"))).isFalse();
    }

    @Test
    void skipsOtherPathsAndFailsClosedOnUndecodablePaths() {
        assertThat(filter.shouldNotFilter(request("/actuator/health"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/open-finance%zz/v1"))).isFalse();
    }

    private static MockHttpServletRequest request(String rawUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", rawUri);
        request.setRequestURI(rawUri);
        return request;
    }
}
