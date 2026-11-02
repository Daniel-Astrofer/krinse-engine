package com.kerosene.kfe.liquidity.domain.treasury;

import org.junit.jupiter.api.Test;
import com.kerosene.kfe.liquidity.domain.treasury.TreasuryPlanner.Channel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static com.kerosene.kfe.liquidity.domain.treasury.TreasuryPlanner.Action.*;

class TreasuryPlannerTest {
    private final Instant now=Instant.parse("2026-10-28T12:00:00Z");
    private final String peerA="02"+"a".repeat(64), peerB="03"+"b".repeat(64);
    private final TreasuryPlanner planner=new TreasuryPlanner();
    private TreasuryPolicy policy() { return new TreasuryPolicy("v1",UUID.randomUUID(),100000,
            100000,500000,100000,1000,5,10000,5000,1000,120,List.of(peerA,peerB)); }
    private TreasuryPlanner.Snapshot snapshot(List<Channel> channels) {
        return new TreasuryPlanner.Snapshot(now,now,1000000L,1000000L,0,true,false,channels,List.of());
    }
    private Channel channel(String point,String peer,long local,long remote) {
        return new Channel(point,peer,true,1000000,local,remote,0);
    }
    @Test void emptyInventoryProposesBoundedChannelWithoutExecuting() {
        var plan=planner.plan(policy(),snapshot(List.of()),now);
        assertThat(plan.action()).isEqualTo(OPEN_CHANNEL);
        assertThat(plan.mode()).isEqualTo("PROPOSE_ONLY");
        assertThat(plan.amountSats()).isEqualTo(500000);
        assertThat(plan.peerPubkey()).isEqualTo(peerA);
    }
    @Test void prefersCircularLiquidityBeforeAllocatingMoreCapital() {
        var snap=snapshot(List.of(channel("b",peerB,800000,200000),channel("a",peerA,100000,900000)));
        var p=policy(); var plan=planner.plan(p,snap,now);
        assertThat(plan.action()).isEqualTo(REBALANCE);
        assertThat(plan.channelPoint()).isEqualTo("a");
        assertThat(plan.amountSats()).isEqualTo(100000);
        assertThat(planner.plan(p,snapshot(snap.channels().reversed()),now)).isEqualTo(plan);
    }
    @Test void inboundDeficitDoesNotProposeLocallyFundedChannel() {
        assertThat(planner.plan(policy(),snapshot(List.of(channel("a",peerA,950000,50000))),now).action()).isEqualTo(NOOP);
    }
    @Test void unknownIsDifferentFromZero() {
        var snap=new TreasuryPlanner.Snapshot(now,null,null,null,0,false,false,List.of(),List.of());
        assertThat(planner.plan(policy(),snap,now).reasons()).contains("CAPITAL_UNKNOWN","LIGHTNING_UNAVAILABLE");
    }
    @Test void staleOrFutureObservationBlocks() {
        for (Instant at:List.of(now.minusSeconds(121),now.plusSeconds(1))) {
            var snap=new TreasuryPlanner.Snapshot(now,at,1000000L,1000000L,0,true,false,List.of(),List.of());
            assertThat(planner.plan(policy(),snap,now).action()).isEqualTo(BLOCKED);
        }
    }
    @Test void accountingProfitOrMissingBackingCannotFundChannels() {
        var snap=new TreasuryPlanner.Snapshot(now,now,1000000L,0L,0,true,false,List.of(),List.of());
        assertThat(planner.plan(policy(),snap,now).reasons()).contains("CAPITAL_NOT_RECONCILED");
    }
    @Test void unresolvedOperationsBlockNewAllocations() {
        var snap=new TreasuryPlanner.Snapshot(now,now,1000000L,1000000L,0,true,true,List.of(),List.of());
        assertThat(planner.plan(policy(),snap,now).reasons()).contains("OPERATIONS_REQUIRE_RECONCILIATION");
    }
    @Test void protectsReserveFloorAndMaximumPeerExposure() {
        var snap=new TreasuryPlanner.Snapshot(now,now,150000L,150000L,0,true,false,List.of(),List.of());
        assertThat(planner.plan(policy(),snap,now).reasons()).contains("CAPITAL_FLOOR");
        var p=policy();
        var limited=new TreasuryPolicy(p.version(),p.capitalWalletId(),p.reserveFloorSats(),p.minChannelSats(),
                p.maxChannelSats(),p.maxRebalanceSats(),p.maxFeeSats(),p.maxChannels(),5000,p.targetLocalBps(),p.toleranceBps(),120,p.allowedPeers());
        assertThat(planner.plan(limited,snapshot(List.of()),now).reasons()).contains("NO_PEER_WITHIN_CONCENTRATION_LIMIT");
    }
    @Test void missingPolicyFailsClosedAndOversizedTtlCannotCrashPlanner() {
        var p=new TreasuryPolicy(null,null,0,0,0,0,0,0,0,0,0,Long.MAX_VALUE,List.of());
        assertThat(planner.plan(p,snapshot(List.of()),now).reasons()).contains("POLICY_NOT_CONFIGURED");
    }
    @Test void overflowAndInvalidChannelEvidenceBlock() {
        var snap=new TreasuryPlanner.Snapshot(now,now,Long.MAX_VALUE,Long.MAX_VALUE,1,true,false,List.of(),List.of());
        assertThat(planner.plan(policy(),snap,now).reasons()).contains("AMOUNT_OVERFLOW");
        assertThat(planner.plan(policy(),snapshot(List.of(channel("a",peerA,1000001,1))),now).reasons()).contains("CHANNEL_BALANCE_INVALID");
    }
}
