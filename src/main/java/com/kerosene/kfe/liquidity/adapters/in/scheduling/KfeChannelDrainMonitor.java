package com.kerosene.kfe.liquidity.adapters.in.scheduling;

import com.kerosene.kfe.liquidity.adapters.in.KfeChannelLifecycleService;
import com.kerosene.kfe.liquidity.adapters.out.persistence.KfeChannelRebalanceQueueService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.liquidity.adapters.in.ChannelDecisionResult;
import com.kerosene.kfe.liquidity.adapters.in.KfeChannelDecisionService;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeChannelDecisionResponse;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfePpmAdjustRequest;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeRebalanceChannelRequest;
import com.kerosene.kfe.adapters.out.rail.channels.LightningChannelGateway;

/**
 * Periodic channel health: detect drain, queue rebalances, apply PPM deterrent when enabled.
 */
@Service
public class KfeChannelDrainMonitor {

    private static final Logger log = LoggerFactory.getLogger(KfeChannelDrainMonitor.class);

    private final LightningChannelGateway channelGateway;
    private final KfeChannelDecisionService decisionService;
    private final KfeChannelLifecycleService lifecycleService;
    private final KfeChannelRebalanceQueueService rebalanceQueueService;
    private final boolean enabled;
    private final boolean autoPpm;
    private final boolean autoEnqueueRebalance;
    private final long defaultRebalanceCostSats;
    private final long defaultExpectedGainSats;
    private final long defaultCurrentPpm;

    public KfeChannelDrainMonitor(
            LightningChannelGateway channelGateway,
            KfeChannelDecisionService decisionService,
            KfeChannelLifecycleService lifecycleService,
            KfeChannelRebalanceQueueService rebalanceQueueService,
            @Value("${kfe.channel.drain-monitor.enabled:true}") boolean enabled,
            @Value("${kfe.channel.drain-monitor.auto-ppm:true}") boolean autoPpm,
            @Value("${kfe.channel.drain-monitor.auto-enqueue-rebalance:true}") boolean autoEnqueueRebalance,
            @Value("${kfe.channel.drain-monitor.default-rebalance-cost-sats:5000}") long defaultRebalanceCostSats,
            @Value("${kfe.channel.drain-monitor.default-expected-gain-sats:50000}") long defaultExpectedGainSats,
            @Value("${kfe.channel.drain-monitor.assumed-current-ppm:500}") long defaultCurrentPpm) {
        this.channelGateway = channelGateway;
        this.decisionService = decisionService;
        this.lifecycleService = lifecycleService;
        this.rebalanceQueueService = rebalanceQueueService;
        this.enabled = enabled;
        this.autoPpm = autoPpm;
        this.autoEnqueueRebalance = autoEnqueueRebalance;
        this.defaultRebalanceCostSats = Math.max(0L, defaultRebalanceCostSats);
        this.defaultExpectedGainSats = Math.max(0L, defaultExpectedGainSats);
        this.defaultCurrentPpm = Math.max(0L, defaultCurrentPpm);
    }

    @Scheduled(
            fixedDelayString = "${kfe.channel.drain-monitor.fixed-delay-ms:120000}",
            initialDelayString = "${kfe.channel.drain-monitor.initial-delay-ms:45000}")
    public void scan() {
        if (!enabled || !channelGateway.isLive()) {
            return;
        }
        for (LightningChannelGateway.ChannelSnapshot channel : channelGateway.listChannels()) {
            try {
                inspectChannel(channel);
            } catch (RuntimeException ex) {
                log.warn(
                        "[KFE Channel Drain] failed channelPoint={}: {}",
                        channel.channelPoint(),
                        ex.getMessage());
            }
        }
    }

    @Transactional
    public void inspectChannel(LightningChannelGateway.ChannelSnapshot channel) {
        if (channel == null || channel.channelPoint() == null) {
            return;
        }
        // Rebalance decision (drain + profit + fund).
        ChannelDecisionResult rebal = decisionService.evaluateRebalance(
                channel,
                defaultRebalanceCostSats,
                defaultExpectedGainSats);
        if (rebal.passed() && autoEnqueueRebalance) {
            KfeChannelDecisionResponse decision = lifecycleService.evaluateRebalance(
                    new KfeRebalanceChannelRequest(
                            channel.channelPoint(),
                            defaultRebalanceCostSats,
                            defaultExpectedGainSats));
            if (decision.passed()) {
                rebalanceQueueService.enqueueIfAbsent(
                        decision.id(),
                        channel.channelPoint(),
                        channel.remotePubkey(),
                        defaultRebalanceCostSats,
                        defaultExpectedGainSats);
                log.info(
                        "[KFE Channel Drain] rebalance queued channel={} localRatio={}",
                        channel.channelPoint(),
                        String.format("%.4f", channel.localRatio()));
            }
        }

        // Drain deterrent PPM when local side is drained.
        boolean acceleratedDrain = rebal.passed();
        if (acceleratedDrain && autoPpm) {
            KfeChannelDecisionResponse ppm = lifecycleService.adjustPpm(
                    new KfePpmAdjustRequest(
                            channel.channelPoint(),
                            defaultCurrentPpm,
                            true,
                            1000L));
            log.info(
                    "[KFE Channel Drain] ppm adjust channel={} passed={} executed={} reason={}",
                    channel.channelPoint(),
                    ppm.passed(),
                    ppm.executed(),
                    ppm.decisionReason());
        }
    }
}
