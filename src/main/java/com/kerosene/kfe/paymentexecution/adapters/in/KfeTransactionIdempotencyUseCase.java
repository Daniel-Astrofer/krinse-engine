package com.kerosene.kfe.paymentexecution.adapters.in;

import org.springframework.stereotype.Service;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeTransactionResponse;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentExecutionResultMapper;
import com.kerosene.kfe.paymentexecution.application.port.in.GetIdempotentPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.query.GetIdempotentPaymentQuery;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;

/** Preserves legacy idempotency query and request-fingerprint behavior at the API boundary. */
@Service
public class KfeTransactionIdempotencyUseCase {

    /** Participant-scoped application query for a previously completed payment. */
    private final GetIdempotentPaymentUseCase queries;
    /** SHA-256 implementation used to derive the established legacy request fingerprint. */
    private final KfeHashService hashService;

    /** Supplies the query and hashing dependencies used by legacy callers. */
    public KfeTransactionIdempotencyUseCase(
            GetIdempotentPaymentUseCase queries,
            KfeHashService hashService) {
        this.queries = queries;
        this.hashService = hashService;
    }

    /** Returns the owner-scoped transaction bound to this key and fingerprint. */
    public KfeTransactionResponse getExistingByIdempotency(Long userId, String idempotencyKey, String requestHash) {
        return queries.find(new GetIdempotentPaymentQuery(userId, new IdempotencyKey(idempotencyKey), new RequestFingerprint(requestHash)))
                .map(LegacyPaymentExecutionResultMapper::toLegacyResponse)
                .orElseThrow(() -> new IllegalStateException("Idempotency conflict detected, but no record found."));
    }

    /** Hashes the stable legacy request fields with explicit domain and owner separation. */
    public String requestHash(Long userId, KfeSubmitTransactionRequest request) {
        return hashService.sha256(String.join("|",
                "KFE_TX_REQUEST",
                userId.toString(),
                request.rail().name(),
                request.direction().name(),
                String.valueOf(request.sourceWalletId()),
                String.valueOf(request.destinationWalletId()),
                String.valueOf(request.amountSats()),
                String.valueOf(request.networkFeeSats()),
                safe(request.externalReference()),
                safe(request.paymentRequestPublicId()),
                safe(request.memo())));
    }

    /** Normalizes optional strings to the empty value used by the legacy hash format. */
    private String safe(String value) {
        return value != null ? value : "";
    }

}
