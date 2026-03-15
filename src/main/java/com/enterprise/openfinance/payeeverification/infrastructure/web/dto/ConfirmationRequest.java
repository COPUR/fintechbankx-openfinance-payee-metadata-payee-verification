package com.enterprise.openfinance.payeeverification.infrastructure.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ConfirmationRequest(@NotNull @Valid Data Data) {
    public record Data(
            @NotBlank String Identification,
            @NotBlank String SchemeName,
            @NotBlank String Name
    ) {
    }
}
