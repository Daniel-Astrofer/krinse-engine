package com.kerosene.kfe.adapters.in.http.dto.paymentexecution;

import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * User-facing transaction projection with state, accounting amounts, counterparties, and cancellation affordances.
 * Amounts ending in {@code Sats} are integer satoshis; display values are derived fiat/BTC amounts.
 * @param id stable transaction identifier
 * @param status persisted transaction lifecycle status
 * @param displayStatus coarse UI badge retained for simple list views
 * @param productStatus canonical product lifecycle status for detailed UI behavior
 * @param rail payment rail used to execute the transfer
 * @param direction whether value is incoming or outgoing from the user's perspective
 * @param walletId wallet used as the transaction's primary perspective
 * @param sourceWalletId source wallet, if known
 * @param destinationWalletId destination wallet, if internal
 * @param walletLabel user-facing label of the primary perspective wallet
 * @param sourceWalletLabel display label of the source wallet, if known
 * @param destinationWalletLabel display label of the destination wallet, if known
 * @param counterpartyLabel privacy-safe label for the other party or external destination
 * @param grossAmountSats gross transfer amount before fees
 * @param receiverAmountSats amount expected to reach the receiver
 * @param networkFeeSats network or rail fee charged for execution
 * @param keroseneFeeSats platform service fee
 * @param totalDebitSats total amount debited from the source wallet
 * @param displayBtcUsd BTC/USD display price used for this projection
 * @param displayBtcEur BTC/EUR display price used for this projection
 * @param displayBtcBrl BTC/BRL display price used for this projection
 * @param displayAmountUsd transaction amount formatted in USD
 * @param displayAmountEur transaction amount formatted in EUR
 * @param displayAmountBrl transaction amount formatted in BRL
 * @param quorumProposalHash proposal hash submitted to vault quorum, when applicable
 * @param quorumAckCount number of quorum acknowledgements recorded
 * @param provider provider name selected for the rail
 * @param providerReference provider-side operation identifier
 * @param externalReference external destination, invoice, or rail-specific reference
 * @param memo user-provided transaction memo
 * @param blockchainTxid on-chain transaction ID, when broadcast
 * @param paymentHash Lightning payment or invoice hash, when applicable
 * @param confirmations number of observed blockchain confirmations
 * @param failureCode stable failure category, when execution failed
 * @param failureMessage safe user-facing failure description
 * @param createdAt transaction creation time
 * @param updatedAt time of the latest persisted transaction update
 * @param cancellable whether the UI may offer cancellation for the current state
 * @param cancelTarget resource type the cancellation endpoint will act on
 * @param paymentRequestId linked payment request identifier, when applicable
 * @param paymentRequestPublicId public payment-request reference, when available
 * @param paymentRequestStatus lifecycle status of the linked payment request
 * @param businessStatus business-level settlement status
 * @param networkStatus external rail or chain status
 * @param accountingStatus ledger posting status
 */
public record KfeTransactionResponse(
        UUID id,
        KfeTransactionStatus status,
        String displayStatus,
        String productStatus,
        KfeRail rail,
        KfeDirection direction,
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
