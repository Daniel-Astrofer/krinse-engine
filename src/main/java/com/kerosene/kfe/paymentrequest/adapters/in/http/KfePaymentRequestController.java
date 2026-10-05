package com.kerosene.kfe.paymentrequest.adapters.in.http;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.kfe.adapters.in.http.KfeAuthenticationSupport;
import com.kerosene.kfe.adapters.in.http.dto.paymentrequest.KfeCreatePaymentRequest;
import com.kerosene.kfe.adapters.in.http.dto.paymentrequest.KfePaymentRequestResponse;
import com.kerosene.kfe.paymentrequest.adapters.in.KfePaymentRequestService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/kfe/payment-requests")
public class KfePaymentRequestController {

    private final KfePaymentRequestService paymentRequestService;

    public KfePaymentRequestController(KfePaymentRequestService paymentRequestService) {
        this.paymentRequestService = paymentRequestService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<KfePaymentRequestResponse>> create(
            @Valid @RequestBody KfeCreatePaymentRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "KFE payment request created.",
                        paymentRequestService.create(KfeAuthenticationSupport.authenticatedUserId(authentication), request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<KfePaymentRequestResponse>>> list(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE payment requests retrieved.",
                paymentRequestService.list(KfeAuthenticationSupport.authenticatedUserId(authentication))));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<ApiResponse<KfePaymentRequestResponse>> get(
            @PathVariable UUID requestId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE payment request retrieved.",
                paymentRequestService.get(KfeAuthenticationSupport.authenticatedUserId(authentication), requestId)));
    }

    @PostMapping("/{requestId}/expire")
    public ResponseEntity<ApiResponse<KfePaymentRequestResponse>> expire(
            @PathVariable UUID requestId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE payment request expired.",
                paymentRequestService.expire(KfeAuthenticationSupport.authenticatedUserId(authentication), requestId)));
    }

    @PostMapping("/{requestId}/hide")
    public ResponseEntity<ApiResponse<KfePaymentRequestResponse>> hide(
            @PathVariable UUID requestId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE payment request hidden.",
                paymentRequestService.hide(KfeAuthenticationSupport.authenticatedUserId(authentication), requestId)));
    }

    @PostMapping("/{requestId}/cancel")
    public ResponseEntity<ApiResponse<KfePaymentRequestResponse>> cancel(
            @PathVariable UUID requestId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE payment request cancelled.",
                paymentRequestService.cancel(KfeAuthenticationSupport.authenticatedUserId(authentication), requestId)));
    }
}
