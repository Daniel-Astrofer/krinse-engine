package com.kerosene.kfe.wallet.adapters.in.observation;

import com.kerosene.kfe.ledger.adapters.in.reconciliation.KfeOnchainBalanceSyncService;

import jakarta.annotation.PreDestroy;
import org.bitcoinj.core.NetworkParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Option 1: subscribe to Bitcoin Core ZMQ {@code hashblock} (+ optional {@code rawtx}),
 * filter against monitored cold addresses, and kick a debounced observe/balance refresh.
 *
 * <p>Poll-based {@link KfeOnchainBalanceSyncService} / {@link KfeColdWalletObservationService}
 * remain the safety net if ZMQ is down or events are missed.
 */
@Component
@ConditionalOnProperty(name = "kfe.bitcoin.zmq.enabled", havingValue = "true")
public class KfeBitcoinZmqWatcher implements SmartLifecycle {

    /** Logger for subscription health, dropped sequence ranges, and ingestion failures. */
    private static final Logger log = LoggerFactory.getLogger(KfeBitcoinZmqWatcher.class);

    /** Maps observed cold-wallet addresses and funding transaction IDs back to wallet ownership. */
    private final KfeMonitoredChainAddressIndex addressIndex;
    /** Schedules debounced full observations for balance and confirmation reconciliation. */
    private final KfeColdWalletReactiveRefreshService refreshService;
    /** Optional cold-wallet history observer receiving raw mempool transactions immediately. */
    private final ObjectProvider<KfeColdWalletObservationService> coldObservationService;
    /** Optional custodial/internal deposit observer for immediate zero-confirmation evidence. */
    private final ObjectProvider<KfeCustodialDepositObservationService> custodialDepositObservationService;
    /** Bitcoin Core ZMQ endpoint publishing block-tip notifications, or null when unset. */
    private final String hashblockEndpoint;
    /** Bitcoin Core ZMQ endpoint publishing raw transactions, or null when unset. */
    private final String rawtxEndpoint;
    /** Whether a raw-transaction subscription should be started when its endpoint exists. */
    private final boolean subscribeRawTx;
    /** Bitcoin network parameters used to decode scripts into addresses in raw transactions. */
    private final NetworkParameters networkParameters;
    /** Atomic SmartLifecycle run flag shared by startup, listener loops, and shutdown. */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Daemon listener threads owned by this watcher and joined during shutdown. */
    private final List<Thread> workers = new ArrayList<>();
    /** Most recent sequence number per subscribed topic for gap detection and health reporting. */
    private final Map<String, AtomicInteger> lastSequenceByTopic = new ConcurrentHashMap<>();
    /** Cumulative number of nonconsecutive ZMQ sequence numbers observed since startup. */
    private final AtomicLong zmqSequenceGaps = new AtomicLong(0L);
    /** Shared ZeroMQ context closed to unblock listeners during lifecycle shutdown. */
    private ZContext zContext;

    /**
     * Configures optional ZMQ subscriptions and resolves the script-decoding network.
     * Blank endpoint values are normalized to absent values; actual workers start only when the
     * {@code kfe.bitcoin.zmq.enabled} conditional property enables this component.
     *
     * @param addressIndex monitored cold-wallet address and funding-transaction index
     * @param refreshService debounced block and touched-wallet refresh scheduler
     * @param coldObservationService optional cold-wallet raw-transaction observer
     * @param custodialDepositObservationService optional custodial/internal deposit observer
     * @param hashblockEndpoint endpoint for hashblock subscription, blank to disable it
     * @param rawtxEndpoint endpoint for raw transaction subscription, blank to disable it
     * @param subscribeRawTx whether to subscribe to raw transactions
     * @param bitcoinNetwork Bitcoin network name used to decode output addresses
     */
    public KfeBitcoinZmqWatcher(
            KfeMonitoredChainAddressIndex addressIndex,
            KfeColdWalletReactiveRefreshService refreshService,
            ObjectProvider<KfeColdWalletObservationService> coldObservationService,
            ObjectProvider<KfeCustodialDepositObservationService> custodialDepositObservationService,
            @Value("${kfe.bitcoin.zmq.hashblock:}") String hashblockEndpoint,
            @Value("${kfe.bitcoin.zmq.rawtx:}") String rawtxEndpoint,
            @Value("${kfe.bitcoin.zmq.subscribe-rawtx:true}") boolean subscribeRawTx,
            @Value("${bitcoin.network:mainnet}") String bitcoinNetwork) {
        this.addressIndex = addressIndex;
        this.refreshService = refreshService;
        this.coldObservationService = coldObservationService;
        this.custodialDepositObservationService = custodialDepositObservationService;
        this.hashblockEndpoint = blankToNull(hashblockEndpoint);
        this.rawtxEndpoint = blankToNull(rawtxEndpoint);
        this.subscribeRawTx = subscribeRawTx;
        this.networkParameters = KfeBitcoinZmqTxMatcher.networkParameters(bitcoinNetwork);
    }

    /**
     * Starts the configured topic workers once, after attempting to rebuild the monitored-address index.
     * Missing endpoints leave the component idle; index rebuild failure is logged and does not prevent
     * block notifications or subsequent address-based refreshes from operating.
     */
    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (hashblockEndpoint == null && rawtxEndpoint == null) {
            log.warn("[KFE ZMQ] enabled but no hashblock/rawtx endpoints configured — watcher idle");
            running.set(false);
            return;
        }
        try {
            addressIndex.rebuild();
        } catch (RuntimeException exception) {
            log.warn("[KFE ZMQ] initial address index rebuild failed: {}", exception.getMessage());
        }
        zContext = new ZContext();
        if (hashblockEndpoint != null) {
            // Prefer hashblock (32-byte tip hash). Some local bitcoind images only
            // publish rawblock on the same port — accept either topic name.
            startWorker("kfe-zmq-hashblock", hashblockEndpoint, "hashblock", this::onHashBlock);
            startWorker("kfe-zmq-rawblock", hashblockEndpoint, "rawblock", this::onHashBlock);
        }
        if (subscribeRawTx && rawtxEndpoint != null) {
            startWorker("kfe-zmq-rawtx", rawtxEndpoint, "rawtx", this::onRawTx);
        }
        log.info(
                "[KFE ZMQ] watcher started hashblock={} rawtx={} addresses={}",
                hashblockEndpoint != null,
                subscribeRawTx && rawtxEndpoint != null,
                addressIndex.addressCount());
    }

    /** Stops all ZeroMQ workers and closes their shared socket context. */
    @Override
    public void stop() {
        stopInternal();
    }

    /** Spring destruction hook sharing the same idempotent shutdown path as SmartLifecycle. */
    @PreDestroy
    public void destroy() {
        stopInternal();
    }

    /**
     * Atomically stops the watcher, closes the context to unblock receives, and joins worker threads
     * for at most two seconds each. Repeated calls after shutdown are no-ops.
     */
    private void stopInternal() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (zContext != null) {
            try {
                zContext.close();
            } catch (RuntimeException ignored) {
                // shutdown
            }
            zContext = null;
        }
        for (Thread worker : workers) {
            try {
                worker.interrupt();
                worker.join(2000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        workers.clear();
        log.info("[KFE ZMQ] watcher stopped");
    }

    /** @return whether listener workers are currently considered active */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** @return true so Spring starts this infrastructure listener automatically */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /** @return late startup phase so dependencies and address observations are initialized first */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 50;
    }

    /**
     * Creates and starts one daemon listener thread for a topic/endpoint pair.
     *
     * @param name diagnostic thread name
     * @param endpoint Bitcoin Core ZMQ endpoint
     * @param topic subscribed multipart-frame topic
     * @param handler callback for each matching body frame
     */
    private void startWorker(String name, String endpoint, String topic, PayloadHandler handler) {
        Thread worker = new Thread(() -> listenLoop(endpoint, topic, handler), name);
        worker.setDaemon(true);
        workers.add(worker);
        worker.start();
    }

    /**
     * Reconnects a SUB socket with exponential backoff, drains multipart messages, filters topics,
     * and records nonconsecutive little-endian sequence numbers before dispatching each payload.
     * Callback failures escape to the reconnect handler; polling remains the durable recovery path.
     *
     * @param endpoint ZMQ publisher endpoint
     * @param topic exact subscribed topic name
     * @param handler topic-specific body processor
     */
    private void listenLoop(String endpoint, String topic, PayloadHandler handler) {
        int backoffMs = 500;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try (ZMQ.Socket socket = zContext.createSocket(SocketType.SUB)) {
                socket.setReceiveTimeOut(3000);
                socket.connect(endpoint);
                socket.subscribe(topic.getBytes(StandardCharsets.UTF_8));
                log.info("[KFE ZMQ] subscribed topic={} endpoint={}", topic, endpoint);
                backoffMs = 500;
                long received = 0L;
                while (running.get() && !Thread.currentThread().isInterrupted()) {
                    // Bitcoin Core multipart: [topic][body][sequence]
                    byte[] topicFrame = socket.recv(0);
                    if (topicFrame == null) {
                        continue; // timeout
                    }
                    byte[] body = socket.recv(0);
                    // Capture sequence frame for gap detection (Bitcoin Core sends LE uint32)
                    int sequence = -1;
                    if (socket.hasReceiveMore()) {
                        byte[] seqFrame = socket.recv(0);
                        if (seqFrame != null && seqFrame.length >= 4) {
                            sequence = ByteBuffer.wrap(seqFrame).order(ByteOrder.LITTLE_ENDIAN).getInt();
                        }
                        // Drain any remaining trailing frames
                        while (socket.hasReceiveMore()) {
                            socket.recv(0);
                        }
                    }
                    if (body == null) {
                        continue;
                    }
                    String receivedTopic = new String(topicFrame, StandardCharsets.UTF_8).trim();
                    if (!topic.equalsIgnoreCase(receivedTopic)) {
                        continue;
                    }

                    // Sequence gap detection
                    if (sequence >= 0) {
                        AtomicInteger lastSeq = lastSequenceByTopic.computeIfAbsent(
                                topic, k -> new AtomicInteger(-1));
                        int prev = lastSeq.getAndSet(sequence);
                        if (prev >= 0 && sequence != prev + 1) {
                            zmqSequenceGaps.incrementAndGet();
                            log.warn("[KFE ZMQ] sequence gap detected topic={} expected={} actual={}",
                                    topic, prev + 1, sequence);
                            onSequenceGap(topic);
                        }
                    }

                    received++;
                    if (received == 1L || received % 100L == 0L) {
                        log.info(
                                "[KFE ZMQ] topic={} messages={} bodyBytes={} seq={}",
                                topic,
                                received,
                                body.length,
                                sequence);
                    }
                    handler.handle(body);
                }
            } catch (Exception exception) {
                if (!running.get()) {
                    break;
                }
                log.warn(
                        "[KFE ZMQ] listener error topic={} endpoint={}: {} — retry in {}ms",
                        topic,
                        endpoint,
                        exception.getMessage(),
                        backoffMs);
                sleep(backoffMs);
                backoffMs = Math.min(15_000, backoffMs * 2);
            }
        }
    }

    /**
     * Recovers raw-transaction message loss by scheduling a full refresh of monitored cold wallets.
     * Block gaps need no explicit scan because a later block-tip refresh reconciles chain state.
     *
     * @param topic topic whose sequence counter skipped a value
     */
    private void onSequenceGap(String topic) {
        // For rawtx gaps, trigger full rescan of all monitored addresses to recover
        // missed 0-conf deposit events. Hashblock gaps self-heal on next block.
        if ("rawtx".equalsIgnoreCase(topic)) {
            log.warn("[KFE ZMQ] rawtx sequence gap — triggering full address rescan");
            try {
                refreshService.onWalletsTouched(addressIndex.allColdWalletIds());
            } catch (RuntimeException e) {
                log.error("[KFE ZMQ] failed to trigger rescan after rawtx gap: {}", e.getMessage());
            }
        }
    }

    /** Last known sequence per topic (for health monitoring). */
    public Map<String, Integer> lastSequences() {
        Map<String, Integer> snapshot = new java.util.LinkedHashMap<>();
        lastSequenceByTopic.forEach((k, v) -> snapshot.put(k, v.get()));
        return Map.copyOf(snapshot);
    }

    /** Total sequence gaps detected since startup. */
    public long totalSequenceGaps() {
        return zmqSequenceGaps.get();
    }

    /**
     * Treats a hashblock/rawblock payload as a tip-change signal and requests balance reconciliation.
     * The payload itself is not parsed because the downstream observer queries authoritative chain state.
     *
     * @param body block hash payload from the ZMQ multipart message
     */
    private void onHashBlock(byte[] body) {
        // body is 32-byte block hash — we only need the tip signal
        log.debug("[KFE ZMQ] hashblock signal ({} bytes)", body == null ? 0 : body.length);
        refreshService.onNewBlock();
    }

    /**
     * Parses a mempool transaction, matches owned outputs or spends of known funding transactions,
     * immediately submits zero-confirmation evidence to available observers, then schedules a debounced
     * authoritative refresh for matching wallets. Unmatched and undecodable transactions are ignored.
     *
     * @param body serialized Bitcoin transaction payload from the rawtx topic
     */
    private void onRawTx(byte[] body) {
        if (body == null || body.length == 0) {
            return;
        }
        KfeBitcoinZmqTxMatcher.ParsedRawTx parsed =
                KfeBitcoinZmqTxMatcher.parse(body, networkParameters);
        if (parsed == null) {
            return;
        }
        Set<String> outputs = new java.util.LinkedHashSet<>();
        for (KfeBitcoinZmqTxMatcher.ParsedOutput out : parsed.outputs()) {
            if (out.address() != null && !out.address().isBlank()) {
                outputs.add(out.address());
            }
        }
        Set<UUID> hits = new java.util.HashSet<>();
        if (!outputs.isEmpty()) {
            hits.addAll(addressIndex.walletIdsForAddresses(outputs));
        }
        // Electrum spends: inputs spend our known cold funding txids (no change required).
        Set<String> fundingTxids = new java.util.LinkedHashSet<>();
        for (KfeBitcoinZmqTxMatcher.ParsedInput in : parsed.inputs()) {
            if (in.fundingTxid() != null && !in.fundingTxid().isBlank()) {
                fundingTxids.add(in.fundingTxid());
            }
        }
        if (!fundingTxids.isEmpty()) {
            hits.addAll(addressIndex.walletIdsForFundingTxids(fundingTxids));
        }
        if (hits.isEmpty()) {
            return;
        }
        log.info(
                "[KFE ZMQ] rawtx matched wallets={} outputs={} fundingInputs={} txid={}",
                hits.size(),
                outputs.size(),
                fundingTxids.size(),
                parsed.txid());
        // Instant expose at 0 confs — do not wait for scantxoutset / debounce.
        // Cold: ingest raw outputs into history as VALIDATING.
        KfeColdWalletObservationService coldObs = coldObservationService.getIfAvailable();
        if (coldObs != null) {
            try {
                coldObs.ingestZmqRawTx(parsed, hits);
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE ZMQ] instant cold ingest failed txid={}: {}",
                        parsed.txid(),
                        exception.getMessage());
            }
        }
        // Custodial / INTERNAL: ingest outputs at 0 conf immediately (listunspent often
        // lags mempool). Debounced observeWallet still refreshes confs + available credit.
        KfeCustodialDepositObservationService custodialObs =
                custodialDepositObservationService.getIfAvailable();
        if (custodialObs != null) {
            try {
                custodialObs.ingestZmqRawTx(parsed, hits);
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE ZMQ] instant custodial ingest failed txid={}: {}",
                        parsed.txid(),
                        exception.getMessage());
            }
        }
        // Debounced full observe still runs for balance truth + confirmation refresh.
        refreshService.onWalletsTouched(hits);
    }

    /**
     * Waits before reconnecting and preserves the interrupt flag so the listener loop can terminate.
     *
     * @param ms backoff duration in milliseconds
     */
    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Trims an optional endpoint and converts null/blank configuration to null.
     *
     * @param value configured endpoint text
     * @return trimmed endpoint, or null when unset
     */
    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Receives the body frame of a topic-filtered Bitcoin Core ZMQ multipart notification. */
    @FunctionalInterface
    private interface PayloadHandler {
        /**
         * Processes one raw topic body; implementations must tolerate provider-originated payloads.
         *
         * @param body serialized block identifier or raw transaction bytes
         */
        void handle(byte[] body);
    }
}
