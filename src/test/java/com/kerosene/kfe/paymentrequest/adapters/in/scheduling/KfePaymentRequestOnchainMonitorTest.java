package com.kerosene.kfe.paymentrequest.adapters.in.scheduling;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.ledger.adapters.in.reconciliation.KfeOnchainBalanceSyncService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.ledger.adapters.out.persistence.settlement.KfeFeeSettlementService;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.pricing.adapters.in.KfePricingService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.ledger.adapters.out.persistence.KfeBalanceMovementRecorder;
import com.kerosene.kfe.bootstrap.config.bitcoin.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.rail.onchain.BlockchainClient;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceMovementRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfePaymentRequestOnchainMonitorTest {

    private final KfePaymentRequestRepository paymentRequestRepository = mock(KfePaymentRequestRepository.class);
    private final KfeTransactionRepository transactionRepository = mock(KfeTransactionRepository.class);
    private final KfeWalletRepository walletRepository = mock(KfeWalletRepository.class);
    private final KfeBalanceMovementRepository movementRepository = mock(KfeBalanceMovementRepository.class);
    private final BlockchainClient blockchainClient = mock(BlockchainClient.class);
    private final KfePricingService pricingService = mock(KfePricingService.class);
    private final KfeBalanceService balanceService = mock(KfeBalanceService.class);
    private final KfeBalanceMovementRecorder movementRecorder = mock(KfeBalanceMovementRecorder.class);
    private final KfeFeeSettlementService feeSettlementService = mock(KfeFeeSettlementService.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);
    private final KfeStatementService statementService = mock(KfeStatementService.class);
    private final KfeResponseMapper responseMapper = mock(KfeResponseMapper.class);
    private final KfeDashboardPublisher dashboardPublisher = mock(KfeDashboardPublisher.class);
    private final FinancialNotificationPort notificationPort = mock(FinancialNotificationPort.class);
    private final KfeOnchainBalanceSyncService onchainBalanceSyncService = mock(KfeOnchainBalanceSyncService.class);

    {
        // record() returns boolean; default mock is false and would skip creditAvailable.
        when(movementRecorder.record(any(), any(), anyString(), anyLong(), isNull(), anyString()))
                .thenReturn(true);
        when(responseMapper.buildDisplayPayload(any(), anyLong())).thenReturn(java.util.Map.of("status", "VALIDATING"));
    }

    private final KfePaymentRequestOnchainMonitor monitor = new KfePaymentRequestOnchainMonitor(
            paymentRequestRepository,
            transactionRepository,
            walletRepository,
            movementRepository,
            provider(blockchainClient),
            pricingService,
            balanceService,
            movementRecorder,
            feeSettlementService,
            auditLogService,
            statementService,
            responseMapper,
            dashboardPublisher,
            notificationPort,
            transactionTemplate(),
            provider(onchainBalanceSyncService),
            provider(null),
            50,
            finalityPolicy());

    private static KfeBitcoinFinalityPolicy finalityPolicy() {
        KfeBitcoinFinalityPolicy policy = new KfeBitcoinFinalityPolicy();
        policy.setCreditConfirmations(3);
        return policy;
    }

    @Test
    void recordsObservedPaymentBeforeMinimumConfirmationsWithoutCrediting() throws Exception {
        KfePaymentRequestEntity request = paymentRequest(10_000L);
        when(paymentRequestRepository.findByStatusInAndRailOrderByCreatedAtAsc(
                eq(List.of(KfePaymentRequestStatus.OPEN, KfePaymentRequestStatus.EXPIRED)),
                eq(KfeRail.ONCHAIN),
                any()))
                .thenReturn(List.of(request));
        when(paymentRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(blockchainClient.getAddressTransactions(request.getAddress()))
                .thenReturn(jsonArray("""
                        [{"txid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","amount":0.0001,"confirmations":2}]
                        """));
        when(transactionRepository.findByProviderReferenceForUpdate(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).thenReturn(List.of());
        when(pricingService.quote(KfeRail.ONCHAIN, com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection.INBOUND, 10_000L, 0L))
                .thenReturn(new KfePricingService.Quote(10_000L, 9_910L, 0L, 0L, 90L));
        when(transactionRepository.save(any(KfeTransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        monitor.reconcileOpenOnchainPaymentRequests();

        assertThat(request.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        verify(balanceService, never()).creditAvailable(any(), any(), anyLong());
        verify(transactionRepository).save(any(KfeTransactionEntity.class));
        verify(statementService).recordUserStatement(
                eq(request.getUserId()),
                eq(request.getWalletId()),
                any(KfeTransactionEntity.class),
                anyMap());
    }

    @Test
    void continuesReconcilingAfterOnePaymentRequestFails() throws Exception {
        KfePaymentRequestEntity failingRequest = paymentRequest(10_000L);
        failingRequest.setAddress("tb1qfailingrequest");
        KfePaymentRequestEntity validRequest = paymentRequest(20_000L);
        validRequest.setAddress("tb1qvalidrequest");
        String failingTxid = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String validTxid = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

        when(paymentRequestRepository.findByStatusInAndRailOrderByCreatedAtAsc(
                eq(List.of(KfePaymentRequestStatus.OPEN, KfePaymentRequestStatus.EXPIRED)),
                eq(KfeRail.ONCHAIN),
                any()))
                .thenReturn(List.of(failingRequest, validRequest));
        when(blockchainClient.getAddressTransactions(failingRequest.getAddress()))
                .thenReturn(jsonArray("""
                        [{"txid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","amount":0.0001,"confirmations":0}]
                        """));
        when(blockchainClient.getAddressTransactions(validRequest.getAddress()))
                .thenReturn(jsonArray("""
                        [{"txid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","amount":0.0002,"confirmations":0}]
                        """));
        when(paymentRequestRepository.findByIdForUpdate(failingRequest.getId()))
                .thenThrow(new IllegalArgumentException("boom"));
        when(paymentRequestRepository.findByIdForUpdate(validRequest.getId()))
                .thenReturn(Optional.of(validRequest));
        when(transactionRepository.findByProviderReferenceForUpdate(failingTxid)).thenReturn(List.of());
        when(transactionRepository.findByProviderReferenceForUpdate(validTxid)).thenReturn(List.of());
        when(pricingService.quote(KfeRail.ONCHAIN, com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection.INBOUND, 20_000L, 0L))
                .thenReturn(new KfePricingService.Quote(20_000L, 19_820L, 0L, 0L, 180L));
        when(transactionRepository.save(any(KfeTransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        monitor.reconcileOpenOnchainPaymentRequests();

        verify(transactionRepository).save(any(KfeTransactionEntity.class));
        verify(statementService).recordUserStatement(
                eq(validRequest.getUserId()),
                eq(validRequest.getWalletId()),
                any(KfeTransactionEntity.class),
                anyMap());
    }

    @Test
    void settlesConfirmedPaymentRequestAndCreditsWallet() throws Exception {
        KfePaymentRequestEntity request = paymentRequest(10_000L);
        UUID requestId = request.getId();
        when(paymentRequestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        when(transactionRepository.findByProviderReferenceForUpdate(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).thenReturn(List.of());
        when(pricingService.quote(KfeRail.ONCHAIN, com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection.INBOUND, 10_000L, 0L))
                .thenReturn(new KfePricingService.Quote(10_000L, 9_910L, 0L, 0L, 90L));
        when(transactionRepository.save(any(KfeTransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        monitor.settlePaymentRequest(requestId, new KfePaymentRequestOnchainMonitor.ObservedPayment(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                10_000L,
                3,
                "{}"));

        assertThat(request.getStatus()).isEqualTo(KfePaymentRequestStatus.PAID);
        assertThat(request.getPaidTransactionId()).isNotNull();
        verify(balanceService).creditAvailable(request.getWalletId(), "BTC", 9_910L);
        verify(feeSettlementService).creditKeroseneFee(any(KfeTransactionEntity.class));
        verify(notificationPort).notifyPaymentRequestDepositConfirmed(
                eq(request.getUserId()),
                any(UUID.class),
                eq(request.getId()),
                eq(request.getPublicId()),
                eq(request.getWalletId()),
                eq("ONCHAIN"),
                eq(9_910L));
    }

    @Test
    void settlesExpiredPaymentRequestWhenOnchainPaymentWasAlreadyObserved() {
        KfePaymentRequestEntity request = paymentRequest(10_000L);
        request.setStatus(KfePaymentRequestStatus.EXPIRED);
        UUID requestId = request.getId();
        when(paymentRequestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        when(transactionRepository.findByProviderReferenceForUpdate(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).thenReturn(List.of());
        when(pricingService.quote(KfeRail.ONCHAIN, com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection.INBOUND, 10_000L, 0L))
                .thenReturn(new KfePricingService.Quote(10_000L, 9_910L, 0L, 0L, 90L));
        when(transactionRepository.save(any(KfeTransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        monitor.settlePaymentRequest(requestId, new KfePaymentRequestOnchainMonitor.ObservedPayment(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                10_000L,
                3,
                "{}"));

        assertThat(request.getStatus()).isEqualTo(KfePaymentRequestStatus.PAID);
        assertThat(request.getPaidTransactionId()).isNotNull();
        verify(balanceService).creditAvailable(request.getWalletId(), "BTC", 9_910L);
    }

    @Test
    void promotesObservedTransactionToSettledWithoutCreatingDuplicate() {
        KfePaymentRequestEntity request = paymentRequest(10_000L);
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(request.getUserId());
        tx.setIdempotencyKey("payment-request:" + request.getId() + ":aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection.INBOUND);
        tx.setDestinationWalletId(request.getWalletId());
        tx.setProviderReference("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        tx.setBlockchainTxid("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        tx.setStatus(KfeTransactionStatus.VALIDATING);
        tx.setConfirmations(1);

        when(paymentRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(transactionRepository.findByProviderReferenceForUpdate(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).thenReturn(List.of(tx));
        when(pricingService.quote(KfeRail.ONCHAIN, com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection.INBOUND, 10_000L, 0L))
                .thenReturn(new KfePricingService.Quote(10_000L, 9_910L, 0L, 0L, 90L));
        when(transactionRepository.save(any(KfeTransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        monitor.settlePaymentRequest(request.getId(), new KfePaymentRequestOnchainMonitor.ObservedPayment(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                10_000L,
                3,
                "{}"));

        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
        assertThat(tx.getConfirmations()).isEqualTo(3);
        assertThat(request.getStatus()).isEqualTo(KfePaymentRequestStatus.PAID);
        verify(balanceService).creditAvailable(request.getWalletId(), "BTC", 9_910L);
    }

    private KfePaymentRequestEntity paymentRequest(Long amountSats) {
        KfePaymentRequestEntity request = new KfePaymentRequestEntity();
        request.setPublicId("public-id");
        request.setUserId(42L);
        request.setWalletId(UUID.randomUUID());
        request.setAddressId(UUID.randomUUID());
        request.setAddress("tb1qpaymentrequest");
        request.setRail(KfeRail.ONCHAIN);
        request.setStatus(KfePaymentRequestStatus.OPEN);
        request.setAmountSats(amountSats);
        return request;
    }

    private JsonNode jsonArray(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    private <T> ObjectProvider<T> provider(T bean) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return bean;
            }

            @Override
            public T getIfAvailable() {
                return bean;
            }

            @Override
            public T getIfUnique() {
                return bean;
            }

            @Override
            public T getObject() {
                return bean;
            }
        };
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        });
    }
}
