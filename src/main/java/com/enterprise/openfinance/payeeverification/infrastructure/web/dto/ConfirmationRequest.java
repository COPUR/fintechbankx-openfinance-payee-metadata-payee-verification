package com.enterprise.openfinance.payeeverification.infrastructure.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ConfirmationRequest(@NotNull @Valid Data Data) {
    public record Data(
            @NotBlank @Size(max = 64) String Identification,
            @NotBlank @Size(max = 32) String SchemeName,
            @NotBlank @Size(max = 140) String Name
    ) {
        @Override
        public String toString() {
            // Name is personal data and Identification is confidential: never print them.
            return "ConfirmationRequest.Data[SchemeName=" + SchemeName + "]";
        }
    }
}
