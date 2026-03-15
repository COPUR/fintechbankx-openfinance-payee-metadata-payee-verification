package com.enterprise.openfinance.payeeverification.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ApiHeaderGuardInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        require(request, "Authorization");
        require(request, "DPoP");
        require(request, "X-FAPI-Interaction-ID");
        return true;
    }

    private void require(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            throw new MissingSecurityHeaderException("Missing required header: " + name);
        }
    }
}
