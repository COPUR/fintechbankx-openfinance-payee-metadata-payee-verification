package com.enterprise.openfinance.payeeverification.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Headers every call must carry. A missing Authorization is an authentication
 * failure (401); a missing X-FAPI-Interaction-ID is missing request input (400).
 * DPoP is checked by {@link DpopEnforcementFilter}.
 */
@Component
public class ApiHeaderGuardInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isBlank(request.getHeader("Authorization"))) {
            throw new MissingSecurityHeaderException("Missing required header: Authorization");
        }
        if (isBlank(request.getHeader("X-FAPI-Interaction-ID"))) {
            throw new IllegalArgumentException("Missing required header: X-FAPI-Interaction-ID");
        }
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
