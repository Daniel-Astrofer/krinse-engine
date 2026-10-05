package com.kerosene.kfe.paymentexecution.application.result;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Transport-neutral read model returned by payment execution use cases.
 * It combines lifecycle, wallet, money, market display, provider, cancellation,
 * payment-request, and reconciliation status projections.
 * @param id payment execution identifier
 * @param status authoritative detailed lifecycle status
 * @param displayStatus legacy display status for existing clients
 * @param productStatus product-facing lifecycle category
 * @param rail payment rail used or selected
 * @param direction transfer direction from the account perspective
 * @param walletId compatibility wallet identifier selected for the transaction view
 * @param sourceWalletId source wallet identifier, if any
 * @param destinationWalletId destination wallet identifier, if any
 * @param walletLabel compatibility wallet display label
 * @param sourceWalletLabel source wallet display label
 * @param destinationWalletLabel destination wallet display label
 * @param counterpartyLabel display-safe counterparty description
 * @param grossAmountSats gross amount in integer satoshis
 * @param receiverAmountSats amount credited to the receiver in integer satoshis
 * @param networkFeeSats network fee in integer satoshis
 * @param keroseneFeeSats platform fee in integer satoshis
 * @param totalDebitSats complete debit in integer satoshis
 * @param displayBtcUsd current BTC/USD display rate
 * @param displayBtcEur current BTC/EUR display rate
 * @param displayBtcBrl current BTC/BRL display rate
 * @param displayAmountUsd payment display amount in USD
 * @param displayAmountEur payment display amount in EUR
 * @param displayAmountBrl payment display amount in BRL
 * @param quorumProposalHash proposal digest used for settlement authorization
 * @param quorumAckCount accepted settlement quorum acknowledgements
 * @param provider external provider or rail identifier
 * @param providerReference provider-side operation reference
 * @param externalReference payment destination/reference used by the rail
 * @param memo payment memo associated with the execution
 * @param blockchainTxid observed Bitcoin transaction identifier
 * @param paymentHash observed Lightning payment hash
 * @param confirmations observed chain confirmation count
 * @param failureCode stable failure classification
 * @param failureMessage user-safe or operational failure description
 * @param createdAt execution creation instant
 * @param updatedAt last execution update instant
 * @param cancellable whether current policy allows a cancellation attempt
 * @param cancelTarget aggregate that would be cancelled (request or transaction)
 * @param paymentRequestId linked payment request identity
 * @param paymentRequestPublicId linked client-visible request identifier
 * @param paymentRequestStatus linked request lifecycle status
 * @param businessStatus product/business lifecycle projection
 * @param networkStatus external network/provider lifecycle projection
 * @param accountingStatus ledger/accounting lifecycle projection
 */
public record PaymentExecutionResult(
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
