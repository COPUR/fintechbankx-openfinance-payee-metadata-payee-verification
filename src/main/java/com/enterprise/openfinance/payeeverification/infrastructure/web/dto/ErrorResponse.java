package com.enterprise.openfinance.payeeverification.infrastructure.web.dto;

public record ErrorResponse(String code, String message, String interactionId, String timestamp) {
}
