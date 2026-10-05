package com.kerosene.kfe.paymentexecution.adapters.out.execution;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.bootstrap.adapters.out.observability.KfeFinancialMetrics;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.ledger.adapters.out.persistence.settlement.KfeFeeSettlementService;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import com.kerosene.kfe.liquidity.adapters.out.persistence.KfeLightningLiquidityService;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.pricing.adapters.out.bitcoin.KfeNetworkFeeEstimateService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionSourceWalletPort;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionSourceWalletSnapshot;
import com.kerosene.kfe.ledger.adapters.out.persistence.KfeBalanceMovementRecorder;
import com.kerosene.kfe.audit.KfeAuditEventLogger;
import com.kerosene.kfe.adapters.out.persistence.model.audit.*;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.*;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.*;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.*;
import com.kerosene.kfe.adapters.out.persistence.model.shared.*;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.*;
import com.kerosene.kfe.adapters.out.persistence.repository.audit.*;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.*;
import com.kerosene.kfe.adapters.out.persistence.repository.liquidity.*;
import com.kerosene.kfe.adapters.out.persistence.repository.messaging.*;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.*;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.*;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real helper/policies with mocked boundaries; transaction/rollback isolation requires the separate PG suite. */
class KfeExecutionPreparationAndFeeTest {
    private final KfeExecutionOutboxRepository outboxes = mock(KfeExecutionOutboxRepository.class);
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final ExecutionSourceWalletPort executionWallets = mock(ExecutionSourceWalletPort.class);
    private final ObjectMapper json = new ObjectMapper();
    private final KfeBalanceService balances = mock(KfeBalanceService.class);
    private final KfeBalanceMovementRepository movements = mock(KfeBalanceMovementRepository.class);
    private final KfeBalanceMovementRecorder recorder = mock(KfeBalanceMovementRecorder.class);
    private final KfeFeeSettlementService fees = mock(KfeFeeSettlementService.class);
    private final KfeNetworkFeeEstimateService quotes = mock(KfeNetworkFeeEstimateService.class);
    private final KfeResponseMapper mapper = mock(KfeResponseMapper.class);
    private final KfeHashService hash = mock(KfeHashService.class);
    private final ObjectProvider<KfeLightningLiquidityService> liquidity = provider();
    private final KfeExecutionTransactionHelper helper = new KfeExecutionTransactionHelper(outboxes, transactions,
            wallets, executionWallets, mock(KfeIdempotencyRepository.class), movements, balances, mock(KfeAuditLogService.class),
            mock(KfeStatementService.class), mapper, mock(KfeDashboardPublisher.class), hash, new ObjectMapper(),
            fees, quotes, provider(), liquidity, provider(), provider(), provider(), provider(), provider(), recorder,
            mock(KfeFinancialMetrics.class), mock(KfeAuditEventLogger.class), 8);
    private final UUID token = UUID.randomUUID();
    private final KfeTransactionEntity tx = new KfeTransactionEntity();
    private final KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
    private final KfeWalletEntity wallet = new KfeWalletEntity();

    @BeforeEach void ready() {
        wallet.setUserId(42L);
        wallet.setLabel("source-label");
        wallet.setKind(KfeWalletKind.INTERNAL);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        tx.setUserId(42L);
        tx.setSourceWalletId(wallet.getId());
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);
        tx.setStatus(KfeTransactionStatus.EXECUTING);
        tx.setExternalReference("approved-destination");
        tx.setMemo("memo");
        tx.setIdempotencyKey("key");
        tx.setQuorumProposalHash("proposal-hash");
        tx.setGrossAmountSats(10_000);
        tx.setReceiverAmountSats(10_000);
        tx.setTotalDebitSats(11_900);
        tx.setNetworkFeeSats(1_000);
        tx.setKeroseneFeeSats(900);
        outbox.setTransactionId(tx.getId());
        outbox.setOperation("ONCHAIN_OUTBOUND");
        resetEnvelope();
        outbox.setStatus("PROCESSING");
        outbox.setClaimToken(token);
        outbox.setLeaseExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));
        when(outboxes.findByIdForUpdate(outbox.getId())).thenReturn(Optional.of(outbox));
        when(transactions.findByIdForUpdate(tx.getId())).thenReturn(Optional.of(tx));
        when(executionWallets.findOwned(42L, wallet.getId())).thenAnswer(invocation -> Optional.of(
                new ExecutionSourceWalletSnapshot(wallet.getId(), wallet.getUserId(), wallet.getLabel(), wallet.getAsset(),
                        wallet.getStatus() == KfeWalletStatus.ACTIVE, wallet.getKind() == KfeWalletKind.WATCH_ONLY,
                        wallet.getKind() != null && wallet.isSpendable())));
        when(mapper.buildDisplayPayload(any(), any())).thenReturn(new LinkedHashMap<>());
        when(hash.sha256(anyString())).thenReturn("test-hash");
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach void clearSynchronization() { TransactionSynchronizationManager.clear(); }

    @Test void preparationRetainsProjectionAndLockOrder() {
        outbox.setOperation(" onchain_outbound ");
        var result = helper.prepare(outbox.getId(), token);
        assertThat(result.proceed()).isTrue();
        assertThat(result.operation()).isEqualTo("ONCHAIN_OUTBOUND");
        assertThat(result.transactionId()).isEqualTo(tx.getId());
        assertThat(result.userId()).isEqualTo(42L);
        assertThat(result.sourceWalletId()).isEqualTo(wallet.getId());
        assertThat(result.sourceWalletLabel()).isEqualTo("source-label");
        assertThat(result.externalReference()).isEqualTo("approved-destination");
        assertThat(result.memo()).isEqualTo("memo");
        assertThat(result.idempotencyKey()).isEqualTo("key");
        assertThat(result.quorumProposalHash()).isEqualTo("proposal-hash");
        assertThat(result.amountSats()).isEqualTo(10_000);
        assertThat(result.networkFeeSats()).isEqualTo(1_000);
        assertThat(result.feeRateSatsPerVbyte()).isEqualTo(6L);
        assertThat(result.feeTargetBlocks()).isNull();
        assertThat(result.claimToken()).isEqualTo(token);
        var order = inOrder(outboxes, transactions, executionWallets);
        order.verify(outboxes).findByIdForUpdate(outbox.getId());
        order.verify(transactions).findByIdForUpdate(tx.getId());
        order.verify(executionWallets).findOwned(42L, wallet.getId());
        verifyNoInteractions(quotes);
        noFinancialEffects();
    }

    @Test void legacyHintAliasesAndQuoteValidationRemainSupported() {
        mergeEnvelope("{\"feeRateSatPerVbyte\":7,\"confirmationTarget\":3,\"quoteId\":\"quote\"}");
        var result = helper.prepare(outbox.getId(), token);
        assertThat(result.proceed()).isTrue();
        assertThat(result.feeRateSatsPerVbyte()).isEqualTo(7L);
        assertThat(result.feeTargetBlocks()).isEqualTo(3);
        verify(quotes).validateQuote("quote", 10_000, "approved-destination", "ONCHAIN");
        noFinancialEffects();
    }

    @Test void derivedRateDoesNotOverflowAtLongMax() {
        tx.setNetworkFeeSats(Long.MAX_VALUE - 1L);
        tx.setTotalDebitSats(Long.MAX_VALUE);
        resetEnvelope();
        mergeEnvelope("{\"estimatedVbytes\":3}");
        var result = helper.prepare(outbox.getId(), token);
        assertThat(result.feeRateSatsPerVbyte()).isEqualTo((Long.MAX_VALUE - 1L) / 3L);
        noFinancialEffects();
    }

    @Test void negativeReserveCannotProduceAnExecutablePreparation() {
        tx.setNetworkFeeSats(-1);
        assertThat(helper.prepare(outbox.getId(), token).proceed()).isFalse();
        assertThat(tx.getFailureCode()).isEqualTo("EXECUTION_ENVELOPE_INVALID");
        verifyNoInteractions(executionWallets, quotes);
        noFinancialEffects();
    }

    @ParameterizedTest @EnumSource(value = KfeTransactionStatus.class,
            names = {"EXECUTING", "REQUIRES_RECONCILIATION"}, mode = EnumSource.Mode.EXCLUDE)
    void nonExecutableStatesNeverAccessWalletOrQuote(KfeTransactionStatus status) {
        tx.setStatus(status);
        assertThat(helper.prepare(outbox.getId(), token).proceed()).isFalse();
        assertThat(outbox.getStatus()).isEqualTo(status == KfeTransactionStatus.SETTLED
                || status == KfeTransactionStatus.FAILED ? "DISPATCHED" : "FAILED_FINAL");
        assertThat(tx.getStatus()).isEqualTo(status);
        verifyNoInteractions(wallets, executionWallets, quotes);
        noFinancialEffects();
    }

    @Test void alreadyBroadcastPaymentSkipsEvenAnInvalidPayloadAndDoesNotReleaseReserve() {
        tx.setBlockchainTxid("ab".repeat(32));
        outbox.setPayloadJson("invalid-json");
        assertThat(helper.prepare(outbox.getId(), token).proceed()).isFalse();
        assertThat(outbox.getStatus()).isEqualTo("DISPATCHED");
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
        verifyNoInteractions(wallets, executionWallets, quotes);
        noFinancialEffects();
    }

    @ParameterizedTest @ValueSource(strings = {"ONCHAIN_INBOUND", "LIGHTNING_INBOUND"})
    void inboundUsesTrustedMonitorInsteadOfWalletPreparation(String operation) {
        outbox.setOperation(operation);
        assertThat(helper.prepare(outbox.getId(), token).proceed()).isFalse();
        assertThat(tx.getFailureCode()).isEqualTo("INBOUND_REQUIRES_TRUSTED_MONITOR");
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        verifyNoInteractions(wallets, executionWallets, quotes);
        noFinancialEffects();
    }

    @Test void unsupportedOperationDoesNotPersistRawOperationAndPreparedEvidenceRetainsReserve() {
        outbox.setOperation("UNSUPPORTED secret-provider-payload");
        outbox.setPreparedPayloadHash("prepared-proof");
        assertThat(helper.prepare(outbox.getId(), token).proceed()).isFalse();
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        assertThat(tx.getFailureMessage()).doesNotContain("secret-provider-payload");
        assertThat(outbox.getLastError()).doesNotContain("secret-provider-payload");
        verifyNoInteractions(wallets, executionWallets, quotes);
        noFinancialEffects();
    }

    @Test void invalidQuoteRetainsPreparedReserveAndPersistsOnlyFixedMessage() {
        mergeEnvelope("{\"quoteId\":\"quote\"}");
        outbox.setPreparedPayloadHash("prepared-proof");
        when(quotes.validateQuote(anyString(), anyLong(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("secret-provider-payload"));
        assertThat(helper.prepare(outbox.getId(), token).proceed()).isFalse();
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        assertThat(tx.getFailureCode()).isEqualTo("EXTERNAL_EXECUTION_NOT_PROVEN_ABSENT");
        assertThat(tx.getFailureMessage()).isEqualTo(
                "Prepared or observed external execution requires reconciliation before releasing reserves.");
        assertThat(outbox.getLastError()).doesNotContain("secret-provider-payload");
        noFinancialEffects();
    }

    @Test void unsupportedUnpreparedOperationIsQuarantinedWithoutReturningMoney() {
        outbox.setOperation("UNSUPPORTED secret-provider-payload");
        assertThat(helper.prepare(outbox.getId(), token).proceed()).isFalse();
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        assertThat(tx.getFailureCode()).isEqualTo("EXECUTION_ENVELOPE_INVALID");
        assertThat(tx.getFailureMessage()).isEqualTo("Execution message does not match the authorized payment.");
        assertThat(outbox.getStatus()).isEqualTo("UNKNOWN");
        assertThat(outbox.getLastError()).doesNotContain("secret-provider-payload");
        assertThat(outbox.getNextAttemptAt()).isNull();
        verifyNoInteractions(wallets, executionWallets, quotes);
        noFinancialEffects();
    }

    @Test void invalidUnpreparedQuoteUsesFixedReasonAndExistingFinalFailureEffects() {
        mergeEnvelope("{\"quoteId\":\"quote\"}");
        when(quotes.validateQuote(anyString(), anyLong(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("secret-provider-payload"));
        assertThat(helper.prepare(outbox.getId(), token).proceed()).isFalse();
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.FAILED);
        assertThat(tx.getFailureCode()).isEqualTo("INVALID_QUOTE");
        assertThat(tx.getFailureMessage()).isEqualTo("Fee quote validation failed.");
        assertThat(outbox.getLastError()).isEqualTo("INVALID_QUOTE: Fee quote validation failed.");
        verify(balances).releaseReserved(wallet.getId(), "BTC", 11_900);
        verify(balances, never()).settleReservedDebit(any(), anyString(), anyLong());
        verifyNoInteractions(fees);
    }

    enum Ack { BROADCAST, SETTLE, LIGHTNING }

    private void acknowledge(Ack ack, long actualFee) {
        switch (ack) {
            case BROADCAST -> helper.recordOutboundBroadcast(outbox.getId(), tx.getId(), token, "provider", "reference",
                    "ab".repeat(32), actualFee, wallet.getId(), "{}");
            case SETTLE -> helper.settleOutbound(outbox.getId(), tx.getId(), token, "provider", "reference",
                    "ab".repeat(32), actualFee, wallet.getId(), "{}");
            case LIGHTNING -> helper.settleOutboundLightning(outbox.getId(), tx.getId(), token, "provider", "reference",
                    null, "payment-hash", actualFee, wallet.getId(), "{}");
        }
    }

    @ParameterizedTest @EnumSource(Ack.class)
    void acceptableActualFeeReleasesOnlyUnusedFeeAndPreservesSettlementPath(Ack ack) {
        acknowledge(ack, 700);
        assertThat(tx.getNetworkFeeSats()).isEqualTo(700);
        assertThat(tx.getTotalDebitSats()).isEqualTo(11_600);
        assertThat(outbox.getStatus()).isEqualTo("DISPATCHED");
        verify(balances).releaseReserved(wallet.getId(), "BTC", 300);
        var captured = ArgumentCaptor.forClass(KfeBalanceMovementEntity.class);
        verify(movements, times(ack == Ack.BROADCAST ? 1 : 2)).save(captured.capture());
        assertThat(captured.getAllValues().getFirst().getMovementType()).isEqualTo("RELEASE_FEE_RESERVE");
        assertThat(captured.getAllValues().getFirst().getAmountSats()).isEqualTo(300);
        assertThat(captured.getAllValues().getFirst().getToBucket()).isEqualTo("AVAILABLE");
        assertThat(captured.getAllValues()).allSatisfy(movement -> {
            assertThat(movement.getTransactionId()).isEqualTo(tx.getId());
            assertThat(movement.getWalletId()).isEqualTo(wallet.getId());
            assertThat(movement.getAsset()).isEqualTo("BTC");
            assertThat(movement.getFromBucket()).isEqualTo("LOCKED");
        });
        if (ack == Ack.BROADCAST) {
            assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
            verify(balances, never()).settleReservedDebit(any(), anyString(), anyLong());
            verifyNoInteractions(fees, liquidity);
        } else {
            assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
            verify(balances).settleReservedDebit(wallet.getId(), "BTC", 11_600);
            verify(fees).creditKeroseneFee(tx);
            assertThat(captured.getAllValues().get(1).getMovementType()).isEqualTo("SETTLE_DEBIT");
            assertThat(captured.getAllValues().get(1).getAmountSats()).isEqualTo(11_600);
            assertThat(captured.getAllValues().get(1).getToBucket()).isNull();
        }
        var order = inOrder(outboxes, transactions, balances);
        order.verify(outboxes).findByIdForUpdate(outbox.getId());
        order.verify(transactions).findByIdForUpdate(tx.getId());
        order.verify(balances).releaseReserved(wallet.getId(), "BTC", 300);
    }

    @ParameterizedTest @CsvSource({
            "1000,10000,11900,-1,INVALID_ACTUAL_FEE",
            "-1,10000,11900,0,INVALID_FEE_SNAPSHOT",
            "1000,0,11900,0,INVALID_FEE_SNAPSHOT",
            "1000,10000,0,0,INVALID_FEE_SNAPSHOT",
            "1000,10000,11900,1001,ACTUAL_FEE_EXCEEDS_RESERVED",
            "4000,10000,14900,3001,FEE_EXCEEDS_MAX_RATIO",
            "1000,10000,1000,0,INVALID_RECONCILED_DEBIT"})
    void rejectedFeePreservesAllMoneyAcrossEachAcknowledgement(long reserved, long amount, long debit,
                                                               long actual, String code) {
        for (Ack ack : Ack.values()) {
            tx.setStatus(KfeTransactionStatus.EXECUTING);
            tx.setNetworkFeeSats(reserved);
            tx.setReceiverAmountSats(amount);
            tx.setTotalDebitSats(debit);
            outbox.setStatus("PROCESSING");
            outbox.setClaimToken(token);
            acknowledge(ack, actual);
            assertThat(tx.getNetworkFeeSats()).isEqualTo(reserved);
            assertThat(tx.getTotalDebitSats()).isEqualTo(debit);
            assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.REQUIRES_RECONCILIATION);
            assertThat(tx.getFailureCode()).isEqualTo(code);
            assertThat(outbox.getStatus()).isEqualTo("UNKNOWN");
            assertThat(outbox.getProviderReference()).isEqualTo("reference");
            noFinancialEffects();
        }
    }

    @Test void ratioAtLongMaxDoesNotRejectValidFeeThroughIntermediateOverflow() {
        long fee = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(30))
                .divide(BigInteger.valueOf(100)).longValueExact();
        tx.setNetworkFeeSats(fee);
        tx.setReceiverAmountSats(Long.MAX_VALUE);
        tx.setTotalDebitSats(Long.MAX_VALUE);
        acknowledge(Ack.BROADCAST, fee);
        assertThat(outbox.getStatus()).isEqualTo("DISPATCHED");
        assertThat(tx.getNetworkFeeSats()).isEqualTo(fee);
        assertThat(tx.getTotalDebitSats()).isEqualTo(Long.MAX_VALUE);
        noFinancialEffects();
    }

    @Test void compatibilityValidatorDelegatesAbsoluteAndRatioChecksButDoesNotEnforceSatPerVbyte() {
        assertThat(KfeExecutionTransactionHelper.validateFeeBeforeBroadcast(100, 100, 1_000, 1, 0, 0).valid()).isTrue();
        assertThat(KfeExecutionTransactionHelper.validateFeeBeforeBroadcast(100, 100, 1_000, 0, 99, 0).valid()).isFalse();
        assertThat(KfeExecutionTransactionHelper.validateFeeBeforeBroadcast(100, 100, 1_000, 0, 0, 9).valid()).isFalse();
        assertThat(KfeExecutionTransactionHelper.validateFeeBeforeBroadcast(0, 100, 0, 0, 0, 0).valid()).isFalse();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void changingAnyIntentFieldQuarantinesEvenWhenTheChecksumIsRecomputed(boolean recalculateHash) throws Exception {
        var changes = new LinkedHashMap<String, Object>();
        changes.put("transactionId", UUID.randomUUID().toString());
        changes.put("userId", 43L);
        changes.put("idempotencyKey", " key ");
        changes.put("rail", "LIGHTNING");
        changes.put("direction", "INBOUND");
        changes.put("sourceWalletId", UUID.randomUUID().toString());
        changes.put("destinationWalletId", UUID.randomUUID().toString());
        changes.put("amountSats", 10_001L);
        changes.put("networkFeeSats", 999L);
        changes.put("totalDebitSats", 11_899L);
        changes.put("externalReference", "different-destination");
        changes.put("memo", "different memo");
        changes.put("quorumProposalHash", "different-proposal");
        for (var change : changes.entrySet()) {
            tx.setStatus(KfeTransactionStatus.EXECUTING);
            outbox.setStatus("PROCESSING");
            outbox.setClaimToken(token);
            outbox.setPreparedPayloadHash("retain-prepared-proof");
            resetEnvelope();
            var node = (ObjectNode) json.readTree(outbox.getPayloadJson());
            node.set(change.getKey(), json.valueToTree(change.getValue()));
            String previousHash = outbox.getPayloadHash();
            writeEnvelope(node);
            if (!recalculateHash) { outbox.setPayloadHash(previousHash); }
            assertEnvelopeQuarantined();
            assertThat(outbox.getPreparedPayloadHash()).isEqualTo("retain-prepared-proof");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"operation", "rail", "direction", "proposal", "key", "owner", "source"})
    void inconsistentStoredIntentCannotAuthorizePreparation(String field) {
        switch (field) {
            case "operation" -> outbox.setOperation("LIGHTNING_OUTBOUND");
            case "rail" -> tx.setRail(KfeRail.INTERNAL);
            case "direction" -> tx.setDirection(KfeDirection.INBOUND);
            case "proposal" -> tx.setQuorumProposalHash(null);
            case "key" -> tx.setIdempotencyKey(" ");
            case "owner" -> tx.setUserId(0L);
            case "source" -> tx.setSourceWalletId(null);
        }
        assertEnvelopeQuarantined();
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "[]", "null", "invalid-json", "{\"userId\":42,\"userId\":43}"})
    void invalidEnvelopeWithAValidChecksumIsQuarantined(String raw) {
        outbox.setPayloadJson(raw);
        outbox.setPayloadHash(new KfeHashService().sha256(raw));
        assertEnvelopeQuarantined();
    }

    @Test void absentChecksumIsNotSilentlyAccepted() {
        outbox.setPayloadHash(null);
        assertEnvelopeQuarantined();
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "owner", "id", "archived", "watch-only", "not-spendable", "kind", "asset", "label"})
    void ineligibleFreshWalletNeverReachesQuoteOrMoney(String reason) {
        switch (reason) {
            case "missing" -> when(executionWallets.findOwned(42L, wallet.getId())).thenReturn(Optional.empty());
            case "owner" -> wallet.setUserId(43L);
            case "id" -> when(executionWallets.findOwned(42L, wallet.getId())).thenReturn(Optional.of(
                    new ExecutionSourceWalletSnapshot(UUID.randomUUID(), 42L, "label", "BTC", true, false, true)));
            case "archived" -> wallet.setStatus(KfeWalletStatus.ARCHIVED);
            case "watch-only" -> wallet.setKind(KfeWalletKind.WATCH_ONLY);
            case "not-spendable" -> wallet.setSpendable(false);
            case "kind" -> wallet.setKind(null);
            case "asset" -> wallet.setAsset("USD");
            case "label" -> wallet.setLabel(" ");
        }
        assertThat(helper.prepare(outbox.getId(), token).proceed()).isFalse();
        assertThat(tx.getFailureCode()).isEqualTo("EXECUTION_SOURCE_WALLET_INVALID");
        assertThat(outbox.getStatus()).isEqualTo("UNKNOWN");
        assertThat(outbox.getNextAttemptAt()).isNull();
        assertThat(outbox.getClaimToken()).isNull();
        assertThat(tx.getTotalDebitSats()).isEqualTo(11_900);
        verifyNoInteractions(wallets, quotes);
        noFinancialEffects();
    }

    @Test void databaseFailureIsNotReclassifiedAsAnInvalidWallet() {
        var failure = new IllegalStateException("database unavailable");
        when(executionWallets.findOwned(42L, wallet.getId())).thenThrow(failure);
        assertThatThrownBy(() -> helper.prepare(outbox.getId(), token)).isSameAs(failure);
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
        assertThat(outbox.getClaimToken()).isEqualTo(token);
        verify(transactions, never()).save(any());
        verify(outboxes, never()).save(any());
        verifyNoInteractions(quotes);
        noFinancialEffects();
    }

    @Test void equivalentWireWhitespaceUsesTheStoredAuthorizedReferences() {
        mergeEnvelope("{\"externalReference\":\"  approved-destination  \",\"memo\":\" memo \"}");
        var result = helper.prepare(outbox.getId(), token);
        assertThat(result.proceed()).isTrue();
        assertThat(result.externalReference()).isEqualTo("approved-destination");
        assertThat(result.memo()).isEqualTo("memo");
        noFinancialEffects();
    }

    @ParameterizedTest @EnumSource(value = KfeRail.class, names = {"ONCHAIN", "LIGHTNING"})
    void validPreparedRetryRemainsBoundAndDoesNotChangeStoredProof(KfeRail rail) {
        tx.setRail(rail);
        tx.setStatus(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        outbox.setOperation(rail.name() + "_OUTBOUND");
        outbox.setPreparedPayloadHash("retain-proof");
        outbox.setExecutionReference("reference");
        resetEnvelope();
        assertThat(helper.prepare(outbox.getId(), token).proceed()).isTrue();
        assertThat(outbox.getPreparedPayloadHash()).isEqualTo("retain-proof");
        assertThat(outbox.getExecutionReference()).isEqualTo("reference");
        assertThat(outbox.getClaimToken()).isEqualTo(token);
        noFinancialEffects();
    }

    @Test void acknowledgedOnchainSkipsAnOriginalUnreconciledEnvelopeWithoutReadingWallet() {
        tx.setBlockchainTxid("ab".repeat(32));
        tx.setNetworkFeeSats(700);
        tx.setTotalDebitSats(11_600);
        assertThat(helper.prepare(outbox.getId(), token).proceed()).isFalse();
        assertThat(outbox.getStatus()).isEqualTo("DISPATCHED");
        verifyNoInteractions(wallets, executionWallets, quotes);
        noFinancialEffects();
    }

    private void assertEnvelopeQuarantined() {
        assertThat(helper.prepare(outbox.getId(), token).proceed()).isFalse();
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        assertThat(tx.getFailureCode()).isEqualTo("EXECUTION_ENVELOPE_INVALID");
        assertThat(outbox.getStatus()).isEqualTo("UNKNOWN");
        assertThat(outbox.getNextAttemptAt()).isNull();
        assertThat(outbox.getClaimToken()).isNull();
        assertThat(tx.getNetworkFeeSats()).isEqualTo(1_000);
        assertThat(tx.getTotalDebitSats()).isEqualTo(11_900);
        verifyNoInteractions(wallets, executionWallets, quotes);
        noFinancialEffects();
    }

    private void resetEnvelope() {
        var node = json.createObjectNode();
        node.put("transactionId", tx.getId().toString());
        node.put("userId", tx.getUserId());
        node.put("idempotencyKey", tx.getIdempotencyKey());
        node.put("rail", tx.getRail().name());
        node.put("direction", tx.getDirection().name());
        node.put("sourceWalletId", tx.getSourceWalletId() == null ? null : tx.getSourceWalletId().toString());
        node.put("destinationWalletId", tx.getDestinationWalletId() == null ? null : tx.getDestinationWalletId().toString());
        node.put("amountSats", tx.getReceiverAmountSats());
        node.put("networkFeeSats", tx.getNetworkFeeSats());
        node.put("totalDebitSats", tx.getTotalDebitSats());
        node.put("externalReference", tx.getExternalReference());
        node.put("memo", tx.getMemo());
        node.put("quorumProposalHash", tx.getQuorumProposalHash());
        writeEnvelope(node);
    }

    private void mergeEnvelope(String fields) {
        try {
            ObjectNode node = (ObjectNode) json.readTree(outbox.getPayloadJson());
            node.setAll((ObjectNode) json.readTree(fields));
            writeEnvelope(node);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private void writeEnvelope(ObjectNode node) {
        outbox.setPayloadJson(node.toString());
        outbox.setPayloadHash(new KfeHashService().sha256(outbox.getPayloadJson()));
    }

    private void noFinancialEffects() { verifyNoInteractions(balances, movements, recorder, fees, liquidity); }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() { return mock(ObjectProvider.class); }
}
