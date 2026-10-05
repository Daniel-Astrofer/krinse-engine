package com.kerosene.kfe.paymentexecution.adapters.in.http;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * HTTP representation shared by submit, query and cancellation endpoints.
 * @param sourceWalletId source wallet identifier, when applicable
 * @param destinationWalletId destination wallet identifier, when applicable
 * @param counterpartyLabel counterparty display label, when resolved
 * @param receiverAmountSats amount expected to reach the receiver, excluding applicable fees
 * @param totalDebitSats total amount debited from the source, including applicable fees
 * @param displayBtcUsd display BTC/USD rate captured for the execution, when available
 * @param displayBtcEur display BTC/EUR rate captured for the execution, when available
 * @param displayBtcBrl display BTC/BRL rate captured for the execution, when available
 * @param displayAmountUsd display conversion of the payment amount to USD, when available
 * @param displayAmountEur display conversion of the payment amount to EUR, when available
 * @param displayAmountBrl display conversion of the payment amount to BRL, when available
 * @param quorumProposalHash hash identifying the quorum proposal, if a quorum flow was used
 * @param provider provider selected for the external execution, if applicable
 * @param failureCode stable machine-readable failure code, if execution failed
 * @param id Stable execution identifier.
 * @param status Current internal lifecycle status.
 * @param displayStatus Human-readable lifecycle status for clients.
 * @param productStatus Product-level status used by established KFE clients.
 * @param rail Rail used or selected for the execution.
 * @param direction Direction of the transfer from the participant's perspective.
 * @param walletId Compatibility wallet identifier exposed by the legacy response contract.
 * @param walletLabel Display label for the compatibility wallet.
 * @param sourceWalletLabel Display label for the source wallet.
 * @param destinationWalletLabel Display label for the destination wallet.
 * @param grossAmountSats Gross transfer amount in satoshis.
 * @param networkFeeSats Network fee reserved or charged in satoshis.
 * @param keroseneFeeSats Kerosene service fee in satoshis.
 * @param quorumAckCount Number of acknowledgements recorded for the quorum proposal.
 * @param providerReference Provider-side reference for reconciliation; may be sensitive operational metadata.
 * @param externalReference External destination or reference supplied for the payment.
 * @param memo User-visible payment memo.
 * @param blockchainTxid Bitcoin transaction identifier once a chain transaction is known.
 * @param paymentHash Lightning payment hash when available.
 * @param confirmations Current on-chain confirmation count.
 * @param failureMessage Bounded human-readable failure summary suitable for the client.
 * @param createdAt Creation timestamp for the execution.
 * @param updatedAt Timestamp of the latest persisted execution update.
 * @param cancellable Whether the current state and policy allow cancellation.
 * @param cancelTarget State or operation that cancellation would target.
 * @param paymentRequestId Internal payment-request identifier associated with this execution.
 * @param paymentRequestPublicId Public payment-request identifier associated with this execution.
 * @param paymentRequestStatus Current status of the associated payment request.
 * @param businessStatus Business workflow status exposed for reconciliation.
 * @param networkStatus Network/provider execution status exposed for reconciliation.
 * @param accountingStatus Accounting projection status exposed for reconciliation.
 */
public record PaymentExecutionResponse(
        UUID id,
        ExecutionStatus status,
        String displayStatus,
        String productStatus,
        PaymentRail rail,
        PaymentDirection direction,
        UUID walletId,
        UUID sourceWalletId,
        UUID destinationWalletId,
        String walletLabel,
        String sourceWalletLabel,
        String destinationWalletLabel,
        String counterpartyLabel,
        long grossAmountSats,
        long receiverAmountSats,
        long networkFeeSats,
        long keroseneFeeSats,
        long totalDebitSats,
        BigDecimal displayBtcUsd,
        BigDecimal displayBtcEur,
        BigDecimal displayBtcBrl,
        BigDecimal displayAmountUsd,
        BigDecimal displayAmountEur,
        BigDecimal displayAmountBrl,
        String quorumProposalHash,
        int quorumAckCount,
        String provider,
        String providerReference,
        String externalReference,
        String memo,
        String blockchainTxid,
        String paymentHash,
        int confirmations,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt,
        boolean cancellable,
        String cancelTarget,
        UUID paymentRequestId,
        String paymentRequestPublicId,
        String paymentRequestStatus,
        String businessStatus,
        String networkStatus,
        String accountingStatus) {
}
