package com.kerosene.kfe.paymentexecution.adapters.in.legacy;

import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeTransactionResponse;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentSubmissionMapper;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentExecutionResultMapper;
import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.SubmitPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.adapters.in.KfeTransactionEngine;
import org.springframework.stereotype.Component;

/**
 * Transitional adapter. It lets the new inbound port own the HTTP boundary while the existing
 * transactional orchestrator is migrated behind it in smaller, verifiable increments.
 */
@Component
public class LegacySubmitPaymentAdapter implements SubmitPaymentUseCase {

    /** Established transactional facade retained until its behavior is migrated into the new core boundary. */
    private final KfeTransactionEngine engine;

    /** Supplies the existing submit implementation behind the new application port. */
    public LegacySubmitPaymentAdapter(KfeTransactionEngine engine) {
        this.engine = engine;
    }

    /** Converts the core command to the legacy request, submits it, and maps the legacy result back. */
    @Override
    public PaymentExecutionResult submit(SubmitPaymentCommand command) {
        KfeTransactionResponse response = engine.submit(
                command.userId(),
                LegacyPaymentSubmissionMapper.toLegacyRequest(command),
                command.deviceHash());
        return LegacyPaymentExecutionResultMapper.toResult(response);
    }

}
