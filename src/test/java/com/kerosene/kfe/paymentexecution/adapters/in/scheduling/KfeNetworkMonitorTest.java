package com.kerosene.kfe.paymentexecution.adapters.in.scheduling;

import com.kerosene.kfe.paymentexecution.adapters.out.settlement.KfeInboundSettlementService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.bootstrap.config.bitcoin.KfeBitcoinFinalityPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.rail.onchain.BlockchainClient;
import com.kerosene.kfe.adapters.out.rail.custody.CustodyGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningInvoiceGateway;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KfeNetworkMonitorTest {

    @Mock
    private KfeExecutionOutboxRepository outboxRepository;

    @Mock
    private KfeTransactionRepository transactionRepository;

    @Mock
    private KfeInboundSettlementService settlementService;

    @Mock
    private ObjectProvider<BlockchainClient> blockchainClientProvider;

    @Mock
    private ObjectProvider<LightningInvoiceGateway> lightningInvoiceGatewayProvider;

    @Mock
    private BlockchainClient blockchainClient;

    @Mock
    private LightningInvoiceGateway lightningInvoiceGateway;

    private ObjectMapper objectMapper = new ObjectMapper();

    private KfeNetworkMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new KfeNetworkMonitor(
                outboxRepository,
                transactionRepository,
                settlementService,
                blockchainClientProvider,
                lightningInvoiceGatewayProvider,
                objectMapper,
                50,
                finalityPolicy()
        );
    }

    private KfeBitcoinFinalityPolicy finalityPolicy() {
        KfeBitcoinFinalityPolicy policy = new KfeBitcoinFinalityPolicy();
        policy.setCreditConfirmations(3);
        return policy;
    }

    @Test
    void shouldHandleNoCandidates() {
        when(outboxRepository.findInboundReconciliationCandidates(anyList(), any())).thenReturn(List.of());
        monitor.reconcileInbound();
        verifyNoInteractions(transactionRepository, settlementService);
    }

    @Test
    void shouldIgnoreIfTransactionNotFound() {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();
        UUID txId = UUID.randomUUID();
        outbox.setTransactionId(txId);

        when(outboxRepository.findInboundReconciliationCandidates(anyList(), any())).thenReturn(List.of(outbox));
        when(transactionRepository.findById(txId)).thenReturn(Optional.empty());

        monitor.reconcileInbound();

        verify(transactionRepository).findById(txId);
        verifyNoInteractions(settlementService);
    }

    @Test
    void shouldSettleLightningInvoiceWhenPaid() throws Exception {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();
        UUID txId = UUID.randomUUID();
        outbox.setTransactionId(txId);
        outbox.setPayloadJson("{\"externalReference\": \"lnbc123\"}");
        outbox.setProviderReference("prov-ref-1");

        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setStatus(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        tx.setRail(KfeRail.LIGHTNING);
        tx.setPaymentHash("hash-1");
        tx.setUserId(99L);

        when(outboxRepository.findInboundReconciliationCandidates(anyList(), any())).thenReturn(List.of(outbox));
        when(transactionRepository.findById(txId)).thenReturn(Optional.of(tx));
        when(lightningInvoiceGatewayProvider.getIfAvailable()).thenReturn(lightningInvoiceGateway);
        when(lightningInvoiceGateway.isLive()).thenReturn(true);
        when(lightningInvoiceGateway.providerName()).thenReturn("MOCK_PROVIDER");

        CustodyGateway.IncomingLightningInvoiceStatus status = new CustodyGateway.IncomingLightningInvoiceStatus("SETTLED", 1000L, java.time.LocalDateTime.now(), "raw_payload_data");
        when(lightningInvoiceGateway.getLightningInvoiceStatus(any())).thenReturn(status);

        monitor.reconcileInbound();

        verify(settlementService).settle(any(KfeInboundSettlementService.InboundSettlementProof.class));
    }

    @Test
    void shouldSettleOnchainWhenConfirmationsMet() throws Exception {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();
        UUID txId = UUID.randomUUID();
        outbox.setTransactionId(txId);
        outbox.setPayloadJson("{\"txid\": \"0000000000000000000000000000000000000000000000000000000000000000\", \"address\": \"bc1qtest\"}");

        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setStatus(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setBlockchainTxid("0000000000000000000000000000000000000000000000000000000000000000");

        when(outboxRepository.findInboundReconciliationCandidates(anyList(), any())).thenReturn(List.of(outbox));
        when(transactionRepository.findById(txId)).thenReturn(Optional.of(tx));
        when(blockchainClientProvider.getIfAvailable()).thenReturn(blockchainClient);

        com.fasterxml.jackson.databind.node.ObjectNode rawTxNode = objectMapper.createObjectNode();
        rawTxNode.put("confirmations", 5);
        rawTxNode.put("amount", 0.05);

        when(blockchainClient.getRawTransaction("0000000000000000000000000000000000000000000000000000000000000000", true)).thenReturn(rawTxNode);

        monitor.reconcileInbound();

        verify(settlementService).settle(any(KfeInboundSettlementService.InboundSettlementProof.class));
    }
}
