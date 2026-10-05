package com.kerosene.kfe.paymentexecution.adapters.legacy;

import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

/** ACL for remaining legacy callers. Values are copied without trimming or normalization. */
public final class LegacyPaymentSubmissionMapper {
    /** Prevents instantiation of the stateless legacy request mapper. */
    private LegacyPaymentSubmissionMapper() {}

    /** Converts every submission input, including sensitive-factor values, without normalization. */
    public static SubmitPaymentCommand toCommand(long userId, KfeSubmitTransactionRequest request, String deviceHash) {
        return new SubmitPaymentCommand(userId, new IdempotencyKey(request.idempotencyKey()),
                PaymentRail.valueOf(request.rail().name()), PaymentDirection.valueOf(request.direction().name()),
                request.sourceWalletId(), request.destinationWalletId(), request.amountSats(),
                request.networkFeeSats(), request.externalReference(), request.memo(), request.totpCode(),
                request.passkeyAssertionJson(), request.confirmationPassphrase(), request.appPin(),
                request.paymentRequestPublicId(), request.feeRateSatPerVbyte(), request.feeTargetBlocks(),
                request.quoteId(), deviceHash);
    }

    /** Maps the command back to the legacy request shape for existing idempotency callers. */
    public static KfeSubmitTransactionRequest toLegacyRequest(SubmitPaymentCommand command) {
        return new KfeSubmitTransactionRequest(command.idempotencyKey().value(),
                KfeRail.valueOf(command.rail().name()), KfeDirection.valueOf(command.direction().name()),
                command.sourceWalletId(), command.destinationWalletId(), command.amountSats(),
                command.networkFeeSats(), command.externalReference(), command.memo(), command.totpCode(),
                command.passkeyAssertionJson(), command.confirmationPassphrase(), command.appPin(),
                command.paymentRequestPublicId(), command.feeRateSatPerVbyte(), command.feeTargetBlocks(),
                command.quoteId());
    }
}
