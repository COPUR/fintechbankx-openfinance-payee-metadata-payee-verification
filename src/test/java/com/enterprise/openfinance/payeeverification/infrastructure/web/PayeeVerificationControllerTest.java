package com.enterprise.openfinance.payeeverification.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PayeeVerificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRejectRequestWhenDpopHeaderMissing() throws Exception {
        String payload = """
                {
                  "Data": {
                    "Identification": "AE29000000123456789",
                    "SchemeName": "IBAN",
                    "Name": "Al Tareq Trading LLC"
                  }
                }
                """;

        mockMvc.perform(post("/open-finance/v1/confirmation-of-payee/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer token")
                        .header("X-FAPI-Interaction-ID", "interaction-1")
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_HEADER_MISSING"));
    }

    @Test
    void shouldReturnMatchForKnownPayee() throws Exception {
        String payload = """
                {
                  "Data": {
                    "Identification": "AE29000000123456789",
                    "SchemeName": "IBAN",
                    "Name": "Al Tareq Trading LLC"
                  }
                }
                """;

        mockMvc.perform(post("/open-finance/v1/confirmation-of-payee/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer token")
                        .header("DPoP", "proof")
                        .header("X-FAPI-Interaction-ID", "interaction-2")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.Data.AccountStatus").value("Active"))
                .andExpect(jsonPath("$.Data.NameMatched").value("Match"))
                .andExpect(jsonPath("$.Data.MatchedName").value("Al Tareq Trading LLC"));
    }

    @Test
    void shouldFailValidationWhenMandatoryFieldsMissing() throws Exception {
        String payload = """
                {
                  "Data": {
                    "SchemeName": "IBAN",
                    "Name": "Al Tareq Trading LLC"
                  }
                }
                """;

        mockMvc.perform(post("/open-finance/v1/confirmation-of-payee/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer token")
                        .header("DPoP", "proof")
                        .header("X-FAPI-Interaction-ID", "interaction-3")
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
