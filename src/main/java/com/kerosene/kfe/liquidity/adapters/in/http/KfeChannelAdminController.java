package com.kerosene.kfe.liquidity.adapters.in.http;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeChannelDecisionResponse;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeChannelSnapshotResponse;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeCloseChannelRequest;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeOpenChannelRequest;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfePpmAdjustRequest;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeRebalanceChannelRequest;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelCapacityJobEntity;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelRebalanceJobEntity;
import com.kerosene.kfe.liquidity.adapters.out.observability.KfeCapacitySignalStore;
import com.kerosene.kfe.liquidity.adapters.in.scheduling.KfeChannelCapacityController;
import com.kerosene.kfe.liquidity.adapters.out.persistence.KfeChannelCapacityQueueService;
import com.kerosene.kfe.liquidity.adapters.in.scheduling.KfeChannelCapacityWorker;
import com.kerosene.kfe.liquidity.adapters.in.KfeChannelLifecycleService;
import com.kerosene.kfe.liquidity.adapters.in.scheduling.KfeChannelRebalanceWorker;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestParam;

/** Administrative HTTP facade for channel state, decisions, and asynchronous capacity/rebalance workers. */
@RestController
@RequestMapping("/api/admin/kfe/channels")
public class KfeChannelAdminController {

    /** Evaluates and executes channel lifecycle commands and projects current channels. */
    private final KfeChannelLifecycleService channelLifecycleService;
    /** Provides manual batch execution and listing for rebalance jobs. */
    private final KfeChannelRebalanceWorker rebalanceWorker;
    /** Reads persisted automatic capacity jobs. */
    private final KfeChannelCapacityQueueService capacityQueue;
    /** Provides manual batch execution for automatic capacity jobs. */
    private final KfeChannelCapacityWorker capacityWorker;
    /** Runs the capacity signal scan when an administrator requests it. */
    private final KfeChannelCapacityController capacityController;
    /** Exposes the recent in-process capacity stress signal snapshot. */
    private final KfeCapacitySignalStore capacitySignals;

    /**
     * Creates the admin facade with lifecycle, queue, worker, and signal collaborators.
     *
     * @param channelLifecycleService channel decisions and lifecycle operations
     * @param rebalanceWorker rebalance queue worker
     * @param capacityQueue capacity intent queue reader
     * @param capacityWorker capacity intent worker
     * @param capacityController signal-driven capacity scanner
     * @param capacitySignals in-process capacity signal accumulator
     */
    public KfeChannelAdminController(
            KfeChannelLifecycleService channelLifecycleService,
            KfeChannelRebalanceWorker rebalanceWorker,
            KfeChannelCapacityQueueService capacityQueue,
            KfeChannelCapacityWorker capacityWorker,
            KfeChannelCapacityController capacityController,
            KfeCapacitySignalStore capacitySignals) {
        this.channelLifecycleService = channelLifecycleService;
        this.rebalanceWorker = rebalanceWorker;
        this.capacityQueue = capacityQueue;
        this.capacityWorker = capacityWorker;
        this.capacityController = capacityController;
        this.capacitySignals = capacitySignals;
    }

    /** Lists the current LND channel snapshots. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<KfeChannelSnapshotResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE lightning channels listed.",
                channelLifecycleService.listChannels()));
    }

    /**
     * Lists the oldest pending rebalance jobs.
     *
     * @param limit maximum results requested; queue service clamps nonpositive values
     * @return standard API envelope with pending jobs
     */
    @GetMapping("/rebalance/jobs")
    public ResponseEntity<ApiResponse<List<KfeChannelRebalanceJobEntity>>> pendingRebalances(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.success(
                "Pending channel rebalance jobs listed.",
                channelLifecycleService.pendingRebalances(limit)));
    }

    /**
     * Manually asks the rebalance worker to process one bounded batch.
     *
     * @param limit maximum pending rows to inspect
     * @return count of jobs whose worker invocation returned normally
     */
    @PostMapping("/rebalance/jobs/process")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> processRebalanceJobs(
            @RequestParam(name = "limit", defaultValue = "5") int limit) {
        int processed = rebalanceWorker.processBatch(limit);
        return ResponseEntity.ok(ApiResponse.success(
                "Rebalance worker batch executed.",
                Map.of("processed", processed)));
    }

    /**
     * Lists pending automatic capacity intents.
     *
     * @param limit maximum results requested
     * @return standard API envelope with pending capacity jobs
     */
    @GetMapping("/capacity/jobs")
    public ResponseEntity<ApiResponse<List<KfeChannelCapacityJobEntity>>> pendingCapacityJobs(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.success(
                "Pending channel capacity jobs listed.",
                capacityQueue.pending(limit)));
    }

    /** Returns the fixed fifteen-minute in-process stress signal snapshot used by automation. */
    @GetMapping("/capacity/signals")
    public ResponseEntity<ApiResponse<KfeCapacitySignalStore.CapacitySignals>> capacitySignals() {
        return ResponseEntity.ok(ApiResponse.success(
                "Capacity stress signals (in-process window).",
                capacitySignals.snapshot(900_000L)));
    }

    /** Triggers a capacity scan synchronously; channel mutations remain queued for workers. */
    @PostMapping("/capacity/scan")
    public ResponseEntity<ApiResponse<Map<String, String>>> capacityScan() {
        capacityController.scan();
        return ResponseEntity.ok(ApiResponse.success(
                "Capacity controller scan executed.",
                Map.of("status", "OK")));
    }

    /**
     * Manually asks the capacity worker to process one bounded job batch.
     *
     * @param limit maximum pending jobs to inspect
     * @return count of jobs whose worker invocation returned normally
     */
    @PostMapping("/capacity/jobs/process")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> processCapacityJobs(
            @RequestParam(name = "limit", defaultValue = "3") int limit) {
        int processed = capacityWorker.processBatch(limit);
        return ResponseEntity.ok(ApiResponse.success(
                "Capacity worker batch executed.",
                Map.of("processed", processed)));
    }

    /**
     * Evaluates an open request and persists its decision without requesting the provider open operation.
     *
     * @param request validated peer, amount, and channel options
     * @return policy decision response
     */
    @PostMapping("/open/evaluate")
    public ResponseEntity<ApiResponse<KfeChannelDecisionResponse>> evaluateOpen(
            @Valid @RequestBody KfeOpenChannelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Channel open decision evaluated.",
                channelLifecycleService.evaluateOpen(request)));
    }

    /**
     * Evaluates and, when allowed, starts or resumes channel opening.
     *
     * @param request validated peer, amount, and channel options
     * @return decision plus provider/workflow execution state
     */
    @PostMapping("/open")
    public ResponseEntity<ApiResponse<KfeChannelDecisionResponse>> open(
            @Valid @RequestBody KfeOpenChannelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Channel open decision processed.",
                channelLifecycleService.openChannel(request)));
    }

    /**
     * Evaluates and records a rebalance decision without enqueueing execution.
     *
     * @param request validated channel point and estimated economics
     * @return policy decision response
     */
    @PostMapping("/rebalance/evaluate")
    public ResponseEntity<ApiResponse<KfeChannelDecisionResponse>> evaluateRebalance(
            @Valid @RequestBody KfeRebalanceChannelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Channel rebalance decision evaluated.",
                channelLifecycleService.evaluateRebalance(request)));
    }

    /**
     * Evaluates and queues a passing rebalance for asynchronous execution.
     *
     * @param request validated channel point and estimated economics
     * @return decision and queue acceptance state
     */
    @PostMapping("/rebalance")
    public ResponseEntity<ApiResponse<KfeChannelDecisionResponse>> rebalance(
            @Valid @RequestBody KfeRebalanceChannelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Channel rebalance decision processed.",
                channelLifecycleService.rebalance(request)));
    }

    /**
     * Evaluates a channel close proposal without executing the close.
     *
     * @param request channel point, offline evidence, and close options
     * @return policy decision response
     */
    @PostMapping("/close/evaluate")
    public ResponseEntity<ApiResponse<KfeChannelDecisionResponse>> evaluateClose(
            @Valid @RequestBody KfeCloseChannelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Channel close decision evaluated.",
                channelLifecycleService.evaluateClose(request)));
    }

    /**
     * Evaluates and performs a passing provider close operation.
     *
     * @param request channel point and close options
     * @return decision and provider close state
     */
    @PostMapping("/close")
    public ResponseEntity<ApiResponse<KfeChannelDecisionResponse>> close(
            @Valid @RequestBody KfeCloseChannelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Channel close decision processed.",
                channelLifecycleService.closeChannel(request)));
    }

    /**
     * Evaluates the PPM adjustment OR rules without applying a channel policy update.
     *
     * @param request channel point and current fee/drain state
     * @return policy decision response
     */
    @PostMapping("/ppm/evaluate")
    public ResponseEntity<ApiResponse<KfeChannelDecisionResponse>> evaluatePpm(
            @Valid @RequestBody KfePpmAdjustRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Channel PPM decision evaluated.",
                channelLifecycleService.evaluatePpm(request)));
    }

    /**
     * Evaluates and applies the recommended forwarding PPM when policy and provider checks pass.
     *
     * @param request channel point and current fee/drain state
     * @return decision and policy update result
     */
    @PostMapping("/ppm")
    public ResponseEntity<ApiResponse<KfeChannelDecisionResponse>> adjustPpm(
            @Valid @RequestBody KfePpmAdjustRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Channel PPM decision processed.",
                channelLifecycleService.adjustPpm(request)));
    }
}
