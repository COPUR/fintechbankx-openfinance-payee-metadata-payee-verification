package com.enterprise.openfinance.payeeverification.infrastructure.web;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class TraceContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceContextFilter.class);
    private final MeterRegistry meterRegistry;

    public TraceContextFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // X-Trace-Id is a request id echoed to the caller; distributed tracing ids
        // (MDC traceId/spanId) belong to Micrometer Tracing and are not touched here.
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || !traceId.matches("[A-Za-z0-9-]{1,64}")) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put("requestId", traceId);
        response.setHeader("X-Trace-Id", traceId);

        Timer.Sample sample = Timer.start(meterRegistry);
        long startNs = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
            sample.stop(meterRegistry.timer("http.server.requests.custom", "uri", request.getRequestURI(), "method", request.getMethod()));
            log.info("event=request_completed requestId={} method={} path={} status={} durationMs={}",
                    traceId, request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
            MDC.remove("requestId");
        }
    }
}
