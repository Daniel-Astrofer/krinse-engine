package com.kerosene.kfe.paymentrequest.adapters.in.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;
import com.kerosene.kfe.adapters.in.http.dto.paymentrequest.KfePublicPaymentRequestResponse;
import com.kerosene.kfe.paymentrequest.adapters.in.KfePaymentRequestService;
import com.kerosene.kfe.paymentrequest.adapters.in.KfePlatformLightningPolicy;

@RestController
@RequestMapping("/api/public/kfe/payment-requests")
public class KfePublicPaymentRequestController {

    private final KfePaymentRequestService paymentRequestService;
    private final KfePlatformLightningPolicy platformLightningPolicy;

    public KfePublicPaymentRequestController(
            KfePaymentRequestService paymentRequestService,
            KfePlatformLightningPolicy platformLightningPolicy) {
        this.paymentRequestService = paymentRequestService;
        this.platformLightningPolicy = platformLightningPolicy;
    }

    /**
     * Resolve a BOLT11 / payment hash to a platform payment request when it is ours.
     * Used by clients to switch Lightning paste/scan to INTERNAL ledger settlement.
     */
    @GetMapping("/lookup")
    public ResponseEntity<ApiResponse<KfePublicPaymentRequestResponse>> lookup(
            @RequestParam("invoice") String invoice) {
        if (invoice == null || invoice.isBlank()) {
            throw new StructuredPlatformException(
                    "invoice query parameter is required.",
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.SYS_INVALID_ARGUMENTS,
                    null);
        }
        KfePublicPaymentRequestResponse found = platformLightningPolicy.resolvePlatformInvoice(invoice.trim())
                .orElseThrow(() -> new StructuredPlatformException(
                        "Not a platform Lightning invoice.",
                        HttpStatus.NOT_FOUND,
                        ErrorCodes.LEDGER_PAYMENT_NOT_FOUND,
                        null));
        return ResponseEntity.ok(ApiResponse.success(
                "Platform payment request resolved from Lightning invoice.",
                found));
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<KfePublicPaymentRequestResponse>> getPublic(@PathVariable String publicId) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE public payment request retrieved.",
                paymentRequestService.publicGet(publicId)));
    }
}
