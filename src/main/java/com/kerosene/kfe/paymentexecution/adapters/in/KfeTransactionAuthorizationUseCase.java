package com.kerosene.kfe.paymentexecution.adapters.in;

import org.springframework.stereotype.Service;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentSubmissionMapper;
import com.kerosene.kfe.paymentexecution.application.port.in.AuthorizePaymentUseCase;

/** Compatibility bridge; policy and factor ordering belong to the payment execution core. */
@Service
public class KfeTransactionAuthorizationUseCase {

    /** Application policy that evaluates required payment authorization factors. */
    private final AuthorizePaymentUseCase authorization;

    /** Supplies the authorization policy delegated to by this compatibility bridge. */
    public KfeTransactionAuthorizationUseCase(AuthorizePaymentUseCase authorization) {
        this.authorization = authorization;
    }

    /** Maps a legacy request and delegates its authorization decision to the core use case. */
    public void authorize(Long userId, KfeSubmitTransactionRequest request, String deviceHash) {
        authorization.authorize(LegacyPaymentSubmissionMapper.toCommand(userId, request, deviceHash));
    }
}
