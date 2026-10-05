package com.kerosene.kfe.adapters.in.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;
import com.kerosene.kfe.paymentexecution.domain.exception.SettlementGateRejectedException;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementFlag;

import java.util.List;
import java.util.Map;

/** Converts expected KFE application exceptions into the common API error envelope. */
@RestControllerAdvice
public class KfeExceptionHandler {

    /** Logger for state conflicts that are rejected at the HTTP boundary. */
    private static final Logger log = LoggerFactory.getLogger(KfeExceptionHandler.class);

    /** Preserves the financial rejection without exposing global balances or probe exception text. */
    @ExceptionHandler(SettlementGateRejectedException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleSettlementGateRejected(
            SettlementGateRejectedException ex) {
        var result = ex.result();
        var flags = result == null ? List.<SettlementFlag>of() : result.failedFlags();
        String code = "SETTLEMENT_GATE_REJECTED";
        String message = "The server's financial safety checks rejected this payment attempt.";
        if (flags.equals(List.of(SettlementFlag.V_RESERVA_MAT))) {
            var reason = result.byFlag().get(SettlementFlag.V_RESERVA_MAT).reason();
            if (reason != null && reason.startsWith("INSOLVENT:")) {
                code = "ERR_KFE_RESERVE_COVERAGE_INSUFFICIENT";
                message = "The reserve coverage required for this payment could not be established.";
            } else {
                code = "ERR_KFE_RESERVE_VERIFICATION_UNAVAILABLE";
                message = "The reserve verification required for this payment could not be completed.";
            }
        }
        log.warn("KFE settlement rejected: code={} flags={}", code, flags);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(message, code,
                Map.of("failedFlags", flags.stream().map(Enum::name).toList())));
    }

    /** Preserves structured status, error code, message, and optional payload from domain-facing errors. */
    @ExceptionHandler(StructuredPlatformException.class)
    public ResponseEntity<ApiResponse<Object>> handleStructuredPlatformException(StructuredPlatformException ex) {
        return ResponseEntity
                .status(ex.getStatus())
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode(), ex.getData()));
    }

    /** Maps Spring status exceptions while ensuring an empty reason receives a stable client message. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String message = ex.getReason() == null || ex.getReason().isBlank()
                ? "KFE request rejected."
                : ex.getReason();
        return ResponseEntity
                .status(status)
                .body(ApiResponse.error(message, ErrorCodes.SYS_INVALID_ARGUMENTS));
    }

    /** Maps invalid caller input to HTTP 400 and the platform invalid-arguments code. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(ex.getMessage(), ErrorCodes.SYS_INVALID_ARGUMENTS));
    }

    /** Maps operations that conflict with current state to HTTP 409 and records a warning. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        log.warn("KFE operation rejected by current state: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage(), ErrorCodes.SYS_INVALID_ARGUMENTS));
    }
}
