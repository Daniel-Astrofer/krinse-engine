package com.kerosene.kfe.adapters.in.http;

import com.kerosene.kfe.paymentexecution.domain.exception.SettlementGateRejectedException;
import com.kerosene.kfe.paymentexecution.domain.model.FlagEvaluation;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementFlag;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementGateEvaluation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KfeSettlementGateExceptionHandlerTest {
    @Test
    void reserveRejectionUsesTheSpecificHttpHandlerAndDoesNotExposeGlobalAmounts() throws Exception {
        var rejection = rejection(FlagEvaluation.fail(SettlementFlag.V_RESERVA_MAT,
                "INSOLVENT:coverage=0.3939,required=1.0000,liabilities=123456789,assets=45678901,buffer=61728394"));
        var mvc = MockMvcBuilders.standaloneSetup(new RejectionController(rejection))
                .setControllerAdvice(new KfeExceptionHandler()).build();
        var response = mvc.perform(get("/test/financial-rejection"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("ERR_KFE_RESERVE_COVERAGE_INSUFFICIENT"))
                .andExpect(jsonPath("$.data.failedFlags[0]").value("V_RESERVA_MAT"))
                .andExpect(jsonPath("$.data.failedFlags.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("123456789", "45678901", "61728394", "coverage=", "INSOLVENT:");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"POR_CHECK_ERROR:private-probe-host", "BALANCE_UNAVAILABLE", "NEGATIVE_AVAILABLE_AFTER"})
    void verificationFailuresDoNotAssertInsolvencyOrLeakProbeErrors(String reason) {
        var response = new KfeExceptionHandler().handleSettlementGateRejected(
                rejection(FlagEvaluation.fail(SettlementFlag.V_RESERVA_MAT, reason)));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getErrorCode()).isEqualTo("ERR_KFE_RESERVE_VERIFICATION_UNAVAILABLE");
        assertThat(response.getBody().getData()).containsOnlyKeys("failedFlags");
        assertThat(response.getBody().getMessage()).doesNotContain("private-probe-host");
    }

    @Test
    void multipleFailuresAreNotReducedToAReserveOnlyError() {
        var response = new KfeExceptionHandler().handleSettlementGateRejected(rejection(
                FlagEvaluation.pass(SettlementFlag.V_IDEMPOTENCIA, "IDEMPOTENCY_RESERVED_DB"),
                FlagEvaluation.fail(SettlementFlag.V_SALDO_DISP, "INSUFFICIENT_AVAILABLE"),
                FlagEvaluation.fail(SettlementFlag.V_RESERVA_MAT, "INSOLVENT:private-snapshot")));
        assertThat(response.getBody().getErrorCode()).isEqualTo("SETTLEMENT_GATE_REJECTED");
        assertThat(response.getBody().getData().get("failedFlags"))
                .isEqualTo(List.of("V_SALDO_DISP", "V_RESERVA_MAT"));
    }

    @Test
    void missingEvaluationStillReturnsARefusal() {
        var response = new KfeExceptionHandler().handleSettlementGateRejected(new SettlementGateRejectedException(null));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getErrorCode()).isEqualTo("SETTLEMENT_GATE_REJECTED");
    }

    @Test
    void unrelatedStateConflictsKeepTheirExistingContract() throws Exception {
        MockMvcBuilders.standaloneSetup(new RejectionController(new IllegalStateException("State conflict")))
                .setControllerAdvice(new KfeExceptionHandler()).build()
                .perform(get("/test/financial-rejection"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SYS_001"))
                .andExpect(jsonPath("$.message").value("State conflict"));
    }

    private SettlementGateRejectedException rejection(FlagEvaluation... flags) {
        return new SettlementGateRejectedException(new SettlementGateEvaluation(List.of(flags), 2, 3));
    }

    @RestController
    static class RejectionController {
        private final RuntimeException rejection;
        RejectionController(RuntimeException rejection) { this.rejection = rejection; }
        @GetMapping("/test/financial-rejection")
        public void reject() { throw rejection; }
    }
}
