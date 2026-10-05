package com.kerosene.kfe.paymentexecution.adapters.out.settlement;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.ledger.adapters.out.persistence.settlement.KfeFeeSettlementService;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.pricing.adapters.in.KfePricingService;

import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.ledger.adapters.out.persistence.KfeBalanceMovementRecorder;
import com.kerosene.kfe.ledger.domain.KfeLedgerMovementTypes;
import com.kerosene.kfe.paymentexecution.adapters.out.rail.KfePlatformOnchainDestinationRouter;
import com.kerosene.kfe.bootstrap.config.bitcoin.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceMovementRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfePlatformPeerInboundServiceTest {
    private static final String ADDRESS = "bcrt1qrecipient";
    private final KfePlatformOnchainDestinationRouter router = mock(KfePlatformOnchainDestinationRouter.class);
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final KfePaymentRequestRepository requests = mock(KfePaymentRequestRepository.class);
    private final KfeBalanceService balances = mock(KfeBalanceService.class);
    private final KfeBalanceMovementRecorder movements = mock(KfeBalanceMovementRecorder.class);
    private final KfeResponseMapper mapper = mock(KfeResponseMapper.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<FinancialNotificationPort> notifications = mock(ObjectProvider.class);
    private final KfePlatformPeerInboundService service = new KfePlatformPeerInboundService(
            router, wallets, transactions, mock(KfeBalanceMovementRepository.class), requests,
            balances, movements, mock(KfePricingService.class), mock(KfeFeeSettlementService.class),
            mock(KfeStatementService.class), mapper, mock(KfeDashboardPublisher.class),
            mock(KfeAuditLogService.class), notifications, new KfeBitcoinFinalityPolicy());
    private final KfeWalletEntity sink = new KfeWalletEntity();
    private final KfeTransactionEntity outbound = new KfeTransactionEntity();
    private final KfeTransactionEntity inbound = new KfeTransactionEntity();

    @BeforeEach
    void arrangeExistingInbound() {
        sink.setUserId(42L);
        outbound.setUserId(7L);
        outbound.setRail(KfeRail.ONCHAIN);
        outbound.setDirection(KfeDirection.OUTBOUND);
        outbound.setExternalReference(ADDRESS);
        outbound.setBlockchainTxid("chain-txid");
        outbound.setReceiverAmountSats(10_000L);
        outbound.setConfirmations(3);
        inbound.setUserId(42L);
        inbound.setDirection(KfeDirection.INBOUND);
        inbound.setDestinationWalletId(sink.getId());
        inbound.setGrossAmountSats(10_000L);
        inbound.setReceiverAmountSats(10_000L);
        inbound.setStatus(KfeTransactionStatus.VALIDATING);
        when(router.findPlatformSinkWalletIdForAddress(ADDRESS)).thenReturn(Optional.of(sink.getId()));
        when(wallets.findById(sink.getId())).thenReturn(Optional.of(sink));
        when(transactions.findByBlockchainTxidAndUserId("chain-txid", 42L)).thenReturn(List.of(inbound));
        when(mapper.buildDisplayPayload(inbound, 42L)).thenReturn(Map.of());
        when(movements.record(inbound.getId(), sink.getId(),
                KfeLedgerMovementTypes.CREDIT_CUSTODIAL_DEPOSIT, 10_000L, null, "AVAILABLE"))
                .thenReturn(true);
    }

    @Test
    void locksRequestsBeforeInboundSettlementAndClosesTheLockedRequest() {
        KfePaymentRequestEntity request = request(KfePaymentRequestStatus.OPEN);
        when(requests.findOpenByAddressAndRailForUpdate(
                ADDRESS, KfePaymentRequestStatus.OPEN, KfeRail.ONCHAIN, 42L))
                .thenReturn(List.of(request));

        service.exposeAfterOutboundBroadcast(outbound);

        var order = inOrder(requests, transactions, balances);
        order.verify(requests).findOpenByAddressAndRailForUpdate(
                ADDRESS, KfePaymentRequestStatus.OPEN, KfeRail.ONCHAIN, 42L);
        order.verify(transactions).findByBlockchainTxidAndUserId("chain-txid", 42L);
        order.verify(balances).creditAvailable(sink.getId(), "BTC", 10_000L);
        order.verify(requests).save(request);
        assertThat(request.getStatus()).isEqualTo(KfePaymentRequestStatus.PAID);
        assertThat(request.getPaidTransactionId()).isEqualTo(inbound.getId());
        verify(requests, never()).findOpenByAddressAndRail(any(), any(), any());
    }

    @Test
    void stillCreditsRealInboundWhenCancellationAlreadyClosedRequest() {
        when(requests.findOpenByAddressAndRailForUpdate(
                ADDRESS, KfePaymentRequestStatus.OPEN, KfeRail.ONCHAIN, 42L)).thenReturn(List.of());

        service.exposeAfterOutboundBroadcast(outbound);

        assertThat(inbound.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
        verify(balances).creditAvailable(sink.getId(), "BTC", 10_000L);
        verify(requests, never()).save(any());
    }

    @Test
    void revalidatesLockedRequestStateBeforeMarkingPaid() {
        KfePaymentRequestEntity request = request(KfePaymentRequestStatus.CANCELLED);
        when(requests.findOpenByAddressAndRailForUpdate(
                ADDRESS, KfePaymentRequestStatus.OPEN, KfeRail.ONCHAIN, 42L))
                .thenReturn(List.of(request));

        service.exposeAfterOutboundBroadcast(outbound);

        assertThat(request.getStatus()).isEqualTo(KfePaymentRequestStatus.CANCELLED);
        assertThat(inbound.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
        verify(requests, never()).save(any());
    }

    private KfePaymentRequestEntity request(KfePaymentRequestStatus status) {
        KfePaymentRequestEntity request = new KfePaymentRequestEntity();
        request.setUserId(42L);
        request.setStatus(status);
        request.setAmountSats(10_000L);
        return request;
    }
}
