package com.kerosene.kfe.paymentexecution.adapters.in;

import org.springframework.stereotype.Service;
import com.kerosene.kfe.paymentexecution.adapters.in.KfeSubmitTransactionUseCase;
import com.kerosene.kfe.paymentexecution.adapters.in.KfeTransactionIdempotencyUseCase;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeTransactionResponse;

/** Compatibility facade retaining the established KFE transaction service entry points. */
@Service
public class KfeTransactionEngine {

    /** Executes the payment submission flow while retaining its legacy request and response types. */
    private final KfeSubmitTransactionUseCase submitTransactionUseCase;
    /** Resolves previously submitted transactions by their idempotency key and fingerprint. */
    private final KfeTransactionIdempotencyUseCase idempotencyUseCase;

    /** Supplies the compatibility use cases delegated to by this facade. */
    public KfeTransactionEngine(
            KfeSubmitTransactionUseCase submitTransactionUseCase,
            KfeTransactionIdempotencyUseCase idempotencyUseCase) {
        this.submitTransactionUseCase = submitTransactionUseCase;
        this.idempotencyUseCase = idempotencyUseCase;
    }

    /** Submits through the legacy API without a device hash. */
    public KfeTransactionResponse submit(Long userId, KfeSubmitTransactionRequest request) {
        return submit(userId, request, null);
    }

    /** Submits through the payment workflow, passing the authenticated device hash when available. */
    public KfeTransactionResponse submit(Long userId, KfeSubmitTransactionRequest request, String deviceHash) {
        return submitTransactionUseCase.submit(userId, request, deviceHash);
    }

    /** Retrieves the matching prior transaction or rejects a key reused for different request data. */
    public KfeTransactionResponse getExistingByIdempotency(Long userId, String idempotencyKey, String requestHash) {
        return idempotencyUseCase.getExistingByIdempotency(userId, idempotencyKey, requestHash);
    }

    /** Computes the canonical fingerprint used to bind a legacy request to its idempotency key. */
    public String requestHash(Long userId, KfeSubmitTransactionRequest request) {
        return idempotencyUseCase.requestHash(userId, request);
    }
}
