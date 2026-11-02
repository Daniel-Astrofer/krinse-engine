package com.kerosene.kfe.liquidity.adapters.in;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.liquidity.domain.treasury.TreasuryPolicy;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.*;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.*;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.liquidity.*;
import com.kerosene.kfe.adapters.out.rail.channels.LightningChannelGateway;
import org.junit.jupiter.api.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;

class KfeTreasuryPlanningServiceTest {
    private final KfeBalanceRepository balances=mock(KfeBalanceRepository.class);
    private final KfeWalletRepository wallets=mock(KfeWalletRepository.class);
    private final LightningChannelGateway gateway=mock(LightningChannelGateway.class);
    private final KfeTreasuryProposalRepository proposals=mock(KfeTreasuryProposalRepository.class);
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private final UUID capital=UUID.randomUUID();
    private KfeWalletEntity wallet;
    private KfeBalanceEntity balance;
    private KfeTreasuryPlanningService service;
    @BeforeEach void setup() {
        wallet=new KfeWalletEntity();wallet.setId(capital);wallet.setKind(KfeWalletKind.SYSTEM_FUNDS);wallet.setStatus(KfeWalletStatus.ACTIVE);
        balance=KfeBalanceEntity.empty(capital,"BTC","a".repeat(64));balance.setAvailableSats(1000000);
        balance.setObservedBalance(1000000);
        balance.setObservedProbeMeta("LIVE_MEMPOOL_AWARE",LocalDateTime.now(ZoneOffset.UTC),"test-observation");
        when(wallets.findById(capital)).thenReturn(Optional.of(wallet));
        when(balances.findById(new KfeBalanceId(capital,"BTC"))).thenReturn(Optional.of(balance));
        when(gateway.isLive()).thenReturn(true);
        when(gateway.observeChannels()).thenReturn(new LightningChannelGateway.ChannelInventory(List.of(),List.of()));
        when(proposals.save(any())).thenAnswer(call -> call.getArgument(0));
        var policy=new TreasuryPolicy("v1",capital,100000,100000,500000,100000,1000,5,10000,5000,1000,120,List.of("02"+"a".repeat(64)));
        service=new KfeTreasuryPlanningService(new KfeTreasuryProperties(policy),balances,wallets,gateway,
                mock(KfeChannelRebalanceJobRepository.class),mock(KfeChannelCapacityJobRepository.class),
                mock(KfeChannelOperationDecisionRepository.class),proposals,mapper,false);
    }
    @Test void persistsFullPolicyAndSnapshotEvidenceWithoutFinancialMutation() throws Exception {
        var proposal=service.propose();
        assertThat(mapper.readTree(proposal.getPlanJson()).path("action").asText()).isEqualTo("OPEN_CHANNEL");
        assertThat(mapper.readTree(proposal.getEvidenceJson()).path("policy").path("capitalWalletId").asText()).isEqualTo(capital.toString());
        assertThat(proposal.getEvidenceHash()).hasSize(64);
        assertThat(proposal.getValidUntil()).isAfter(proposal.getCreatedAt());
        verify(gateway,never()).openChannel(any());
        verify(gateway,never()).submitCircularRebalance(any());
        verify(balances,never()).save(any());
    }
    @Test void unavailableInventoryIsNotInterpretedAsNoChannels() throws Exception {
        when(gateway.observeChannels()).thenThrow(new IllegalStateException("timeout"));
        var proposal=service.propose();
        var plan=mapper.readTree(proposal.getPlanJson());
        assertThat(plan.path("action").asText()).isEqualTo("BLOCKED");
        assertThat(plan.path("reasons").toString()).contains("LIGHTNING_OBSERVATION_FAILED");
    }
    @Test void profitSubledgerAndCustomerWalletsCannotBecomeTreasuryCapital() throws Exception {
        for(var kind:List.of(KfeWalletKind.SYSTEM_PROFIT,KfeWalletKind.CUSTODIAL_ONCHAIN,KfeWalletKind.WATCH_ONLY)) {
            wallet.setKind(kind);
            var plan=mapper.readTree(service.propose().getPlanJson());
            assertThat(plan.path("action").asText()).isEqualTo("BLOCKED");
            assertThat(plan.path("reasons").toString()).contains("CAPITAL_WALLET_NOT_ELIGIBLE");
        }
    }
    @Test void confirmedOnlyProbeCannotHideMempoolSpends() throws Exception {
        balance.setObservedProbeQuality("CONFIRMED_UTXO_SET");
        assertThat(mapper.readTree(service.propose().getPlanJson()).path("action").asText()).isEqualTo("BLOCKED");
    }
    @Test void schedulerIsDisabledByDefault() { service.scheduledPlan();verifyNoInteractions(proposals,gateway); }
}
