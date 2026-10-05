package com.kerosene.kfe.paymentexecution.adapters.in.http;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;

/** Maps validated HTTP data to application commands and application results to API DTOs. */
final class PaymentExecutionHttpMapper {

    /** Prevents construction of this stateless mapping utility. */
    private PaymentExecutionHttpMapper() {
    }

    /** Adds the authenticated owner and device context to the application's submission command. */
    static SubmitPaymentCommand toSubmitCommand(long userId, SubmitPaymentRequest request, String deviceHash) {
        return new SubmitPaymentCommand(
                userId,
                new IdempotencyKey(request.idempotencyKey()),
                request.rail(),
                request.direction(),
                request.sourceWalletId(),
                request.destinationWalletId(),
                request.amountSats(),
                request.networkFeeSats(),
                request.externalReference(),
                request.memo(),
                request.totpCode(),
                request.passkeyAssertionJson(),
                request.confirmationPassphrase(),
                request.appPin(),
                request.paymentRequestPublicId(),
                request.feeRateSatPerVbyte(),
                request.feeTargetBlocks(),
                request.quoteId(),
                deviceHash);
    }

    /** Projects an application result into the stable HTTP response representation. */
    static PaymentExecutionResponse toResponse(PaymentExecutionResult result) {
        return new PaymentExecutionResponse(
                result.id(), result.status(), result.displayStatus(), result.productStatus(),
                result.rail(), result.direction(), result.walletId(), result.sourceWalletId(),
                result.destinationWalletId(), result.walletLabel(), result.sourceWalletLabel(),
                result.destinationWalletLabel(), result.counterpartyLabel(), result.grossAmountSats(),
                result.receiverAmountSats(), result.networkFeeSats(), result.keroseneFeeSats(),
                result.totalDebitSats(), result.displayBtcUsd(), result.displayBtcEur(),
                result.displayBtcBrl(), result.displayAmountUsd(), result.displayAmountEur(),
                result.displayAmountBrl(), result.quorumProposalHash(), result.quorumAckCount(),
                result.provider(), result.providerReference(), result.externalReference(), result.memo(),
                result.blockchainTxid(), result.paymentHash(), result.confirmations(), result.failureCode(),
                result.failureMessage(), result.createdAt(), result.updatedAt(), result.cancellable(),
                result.cancelTarget(), result.paymentRequestId(), result.paymentRequestPublicId(),
                result.paymentRequestStatus(), result.businessStatus(), result.networkStatus(),
                result.accountingStatus());
    }
}
