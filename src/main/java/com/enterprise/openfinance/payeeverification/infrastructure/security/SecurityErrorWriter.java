package com.enterprise.openfinance.payeeverification.infrastructure.security;

import com.enterprise.openfinance.payeeverification.infrastructure.web.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Writes the contract's ErrorResponse for rejections that happen before the controller. */
public class SecurityErrorWriter {

    static final String WWW_AUTHENTICATE = "DPoP algs=\"" + String.join(" ", DpopProofValidator.supportedAlgorithms()) + "\"";

    private final ObjectMapper objectMapper;

    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void unauthorized(HttpServletRequest request, HttpServletResponse response, String code,
                             String message, String dpopError) throws IOException {
        String challenge = dpopError == null ? WWW_AUTHENTICATE : WWW_AUTHENTICATE + ", error=\"" + dpopError + "\"";
        response.setHeader("WWW-Authenticate", challenge);
        write(request, response, HttpServletResponse.SC_UNAUTHORIZED, code, message);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int status, String code,
                      String message) throws IOException {
        String interactionId = request.getHeader("X-FAPI-Interaction-ID");
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (interactionId != null && !interactionId.isBlank()) {
            response.setHeader("X-FAPI-Interaction-ID", interactionId);
        }
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(code, message,
            interactionId == null || interactionId.isBlank() ? "N/A" : interactionId,
            OffsetDateTime.now(ZoneOffset.UTC).toString()));
    }
}
