package com.kerosene.kfe.paymentexecution.adapters.in.http;

import com.kerosene.common.dto.ApiResponse;
import com.kerosene.kfe.paymentexecution.application.port.in.SubmitPaymentUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Validates and accepts payment submissions through the authenticated KFE HTTP API. */
@RestController
@RequestMapping("/kfe/transactions")
public class SubmitPaymentController {

    /** Application boundary that owns submission authorization, idempotency, and persistence. */
    private final SubmitPaymentUseCase submitPayment;

    /** Supplies the application submission boundary. */
    public SubmitPaymentController(SubmitPaymentUseCase submitPayment) {
        this.submitPayment = submitPayment;
    }

    /** Converts the authenticated request to a command and returns its accepted execution. */
    @PostMapping
    public ResponseEntity<ApiResponse<PaymentExecutionResponse>> submit(
            @Valid @RequestBody SubmitPaymentRequest request,
            @RequestHeader(value = "X-Device-Hash", required = false) String deviceHash,
            Authentication authentication) {
        long userId = AuthenticatedUserResolver.userId(authentication);
        PaymentExecutionResponse response = PaymentExecutionHttpMapper.toResponse(
                submitPayment.submit(PaymentExecutionHttpMapper.toSubmitCommand(userId, request, deviceHash)));
        return ResponseEntity.ok(ApiResponse.success("KFE transaction accepted.", response));
    }
}
