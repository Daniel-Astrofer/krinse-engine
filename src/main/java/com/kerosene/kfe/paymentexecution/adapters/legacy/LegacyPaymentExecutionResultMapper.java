package com.kerosene.kfe.paymentexecution.adapters.legacy;

import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeTransactionResponse;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

/** Anti-corruption mapper used while legacy financial orchestration is being decomposed. */
public final class LegacyPaymentExecutionResultMapper {

    /** Prevents instantiation of this stateless anti-corruption mapper. */
    private LegacyPaymentExecutionResultMapper() {
    }

    /** Maps the legacy HTTP response's payment, wallet, settlement, and cancellation fields to domain types. */
    public static PaymentExecutionResult toResult(KfeTransactionResponse response) {
        return new PaymentExecutionResult(
                response.id(),
                ExecutionStatus.valueOf(response.status().name()),
                response.displayStatus(),
                response.productStatus(),
                PaymentRail.valueOf(response.rail().name()),
                PaymentDirection.valueOf(response.direction().name()),
                response.walletId(),
                response.sourceWalletId(),
                response.destinationWalletId(),
                response.walletLabel(),
                response.sourceWalletLabel(),
                response.destinationWalletLabel(),
                response.counterpartyLabel(),
                response.grossAmountSats(),
                response.receiverAmountSats(),
                response.networkFeeSats(),
                response.keroseneFeeSats(),
                response.totalDebitSats(),
                response.displayBtcUsd(),
                response.displayBtcEur(),
                response.displayBtcBrl(),
                response.displayAmountUsd(),
                response.displayAmountEur(),
                response.displayAmountBrl(),
                response.quorumProposalHash(),
                response.quorumAckCount(),
                response.provider(),
                response.providerReference(),
                response.externalReference(),
                response.memo(),
                response.blockchainTxid(),
                response.paymentHash(),
                response.confirmations(),
                response.failureCode(),
                response.failureMessage(),
                response.createdAt(),
                response.updatedAt(),
                response.cancellable(),
                response.cancelTarget(),
                response.paymentRequestId(),
                response.paymentRequestPublicId(),
                response.paymentRequestStatus(),
                response.businessStatus(),
                response.networkStatus(),
                response.accountingStatus());
    }

    /** Converts the application result back to the compatibility response schema and enum types. */
    public static KfeTransactionResponse toLegacyResponse(PaymentExecutionResult result) {
        return new KfeTransactionResponse(
                result.id(),
                com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus.valueOf(result.status().name()),
                result.displayStatus(), result.productStatus(),
                com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail.valueOf(result.rail().name()),
                com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection.valueOf(result.direction().name()),
                result.walletId(), result.sourceWalletId(), result.destinationWalletId(), result.walletLabel(),
                result.sourceWalletLabel(), result.destinationWalletLabel(), result.counterpartyLabel(),
                result.grossAmountSats(), result.receiverAmountSats(), result.networkFeeSats(),
                result.keroseneFeeSats(), result.totalDebitSats(), result.displayBtcUsd(), result.displayBtcEur(),
                result.displayBtcBrl(), result.displayAmountUsd(), result.displayAmountEur(), result.displayAmountBrl(),
                result.quorumProposalHash(), result.quorumAckCount(), result.provider(), result.providerReference(),
                result.externalReference(), result.memo(), result.blockchainTxid(), result.paymentHash(),
                result.confirmations(), result.failureCode(), result.failureMessage(), result.createdAt(),
                result.updatedAt(), result.cancellable(), result.cancelTarget(), result.paymentRequestId(),
                result.paymentRequestPublicId(), result.paymentRequestStatus(), result.businessStatus(),
                result.networkStatus(), result.accountingStatus());
    }
}
