package com.kerosene.kfe.pricing.adapters.out.bitcoin;

import com.kerosene.kfe.pricing.adapters.in.KfePricingService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.adapters.in.http.dto.pricing.KfeFeeQuoteResponse;
import com.kerosene.kfe.adapters.in.http.dto.pricing.KfeFeeTierResponse;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Estimates on-chain fees, prices payments, and issues signed short-lived fee quotes. */
@Service
public class KfeNetworkFeeEstimateService {

    /** Source label for rates returned by Bitcoin Core. */
    static final String BITCOIN_CORE_SOURCE = "BITCOIN_CORE";
    /** Source label used when the estimator falls back to configured rates. */
    static final String FALLBACK_SOURCE = "CONFIGURED_FALLBACK";
    /** Source label for rails where network fees do not apply. */
    static final String NOT_APPLICABLE_SOURCE = "NOT_APPLICABLE";
    /** Source label when a caller-provided fee limit determines the estimate. */
    static final String CLIENT_LIMIT_SOURCE = "CLIENT_LIMIT";
    /** Version of the fee-estimation policy signed into every quote. */
    static final int FEE_ESTIMATE_VERSION = 1;

    /** Optional Bitcoin Core adapter used for target-based fee-rate estimates. */
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider;
    /** Applies current service pricing to the network fee selected by the estimator. */
    private final KfePricingService pricingService;
    /** Transaction virtual-byte assumption used to turn fee rates into satoshi totals. */
    private final int estimatedVbytes;
    /** Conservative multiplier applied to the base fee estimate. */
    private final double safetyMargin;
    /** Confirmation target used for the FAST tier. */
    private final int fastTargetBlocks;
    /** Confirmation target used for the STANDARD tier and selected default quote. */
    private final int standardTargetBlocks;
    /** Confirmation target used for the SLOW tier. */
    private final int slowTargetBlocks;
    /** Configured rate used when a fast Bitcoin Core estimate is unavailable. */
    private final long fallbackFastRate;
    /** Configured rate used when a standard estimate is unavailable. */
    private final long fallbackStandardRate;
    /** Configured rate used when a slow estimate is unavailable. */
    private final long fallbackSlowRate;
    /** Expected mainnet block interval used for ETA calculation. */
    private final long expectedBlockSeconds;
    /** Lifetime after which a generated quote is rejected. */
    private final long quoteTtlSeconds;
    /** Network name used to adjust estimated confirmation timing outside mainnet. */
    private final String bitcoinNetwork;
    /** UTF-8 HMAC key bytes used to sign quote fields. */
    private final byte[] signingSecretBytes;
    /** Process-local lookup of unexpired quote response objects by opaque quote ID. */
    private final Map<String, KfeFeeQuoteResponse> quoteStore = new ConcurrentHashMap<>();

    /** Validates configured estimation bounds and prepares the secret used for quote signatures. */
    public KfeNetworkFeeEstimateService(
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider,
            KfePricingService pricingService,
            @Value("${kfe.fee-estimate.estimated-vbytes:250}") int estimatedVbytes,
            @Value("${kfe.fee-estimate.safety-margin:1.5}") double safetyMargin,
            @Value("${kfe.fee-estimate.fast-target-blocks:2}") int fastTargetBlocks,
            @Value("${kfe.fee-estimate.standard-target-blocks:3}") int standardTargetBlocks,
            @Value("${kfe.fee-estimate.slow-target-blocks:6}") int slowTargetBlocks,
            @Value("${kfe.fee-estimate.fallback-fast-sat-vbyte:25}") long fallbackFastRate,
            @Value("${kfe.fee-estimate.fallback-standard-sat-vbyte:12}") long fallbackStandardRate,
            @Value("${kfe.fee-estimate.fallback-slow-sat-vbyte:6}") long fallbackSlowRate,
            @Value("${kfe.fee-estimate.expected-block-seconds:600}") long expectedBlockSeconds,
            @Value("${kfe.fee-estimate.quote-ttl-seconds:120}") long quoteTtlSeconds,
            @Value("${bitcoin.network:mainnet}") String bitcoinNetwork,
            @Value("${kfe.fee-quote.signing-secret:}") String signingSecret) {
        this.bitcoinCoreProvider = bitcoinCoreProvider;
        this.pricingService = pricingService;
        this.estimatedVbytes = positive(estimatedVbytes, "estimatedVbytes");
        if (safetyMargin < 1.0d || !Double.isFinite(safetyMargin)) {
            throw new IllegalArgumentException("safetyMargin must be >= 1.0");
        }
        this.safetyMargin = safetyMargin;
        this.fastTargetBlocks = positive(fastTargetBlocks, "fastTargetBlocks");
        this.standardTargetBlocks = positive(standardTargetBlocks, "standardTargetBlocks");
        this.slowTargetBlocks = positive(slowTargetBlocks, "slowTargetBlocks");
        this.fallbackFastRate = positive(fallbackFastRate, "fallbackFastRate");
        this.fallbackStandardRate = positive(fallbackStandardRate, "fallbackStandardRate");
        this.fallbackSlowRate = positive(fallbackSlowRate, "fallbackSlowRate");
        this.expectedBlockSeconds = positive(expectedBlockSeconds, "expectedBlockSeconds");
        this.quoteTtlSeconds = positive(quoteTtlSeconds, "quoteTtlSeconds");
        this.bitcoinNetwork = bitcoinNetwork != null ? bitcoinNetwork.trim() : "mainnet";
        this.signingSecretBytes = resolveSigningSecret(signingSecret);
    }

    /** Requires and converts the quote signing secret to UTF-8 key material. */
    private static byte[] resolveSigningSecret(String signingSecret) {
        if (signingSecret == null || signingSecret.isBlank()) {
            throw new IllegalArgumentException("kfe.fee-quote.signing-secret must be configured.");
        }
        return signingSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Generates a persistent, signed quote binding the estimated fees to a specific
     * transaction intent. The quote expires after the configured quote TTL.
     */
    public KfeFeeQuoteResponse quote(
            KfeRail rail,
            KfeDirection direction,
            long amountSats,
            long requestedNetworkFeeSats,
            String userId,
            String walletId,
            String destinationHash) {
        Estimate estimate = estimate(rail, direction, requestedNetworkFeeSats);
        KfePricingService.Quote pricingQuote = pricingService.quote(rail, direction, amountSats, estimate.selectedNetworkFeeSats());

        KfeFeeQuoteResponse quote = new KfeFeeQuoteResponse();
        quote.setQuoteId(UUID.randomUUID().toString());
        quote.setUserId(userId);
        quote.setWalletId(walletId);
        quote.setDestinationHash(destinationHash);
        quote.setRail(rail.name());
        quote.setAmount(BigDecimal.valueOf(amountSats));
        quote.setNetworkFeeSat(estimate.selectedNetworkFeeSats());
        quote.setServiceFeeSat(pricingQuote.keroseneFeeSats());
        quote.setTotalDebitSat(pricingQuote.totalDebitSats());
        quote.setPricingPolicyVersion(pricingQuote.pricingPolicyVersion());
        quote.setFeeEstimateVersion(FEE_ESTIMATE_VERSION);
        quote.setExpiresAt(Instant.now().plusSeconds(quoteTtlSeconds));
        quote.setSignature(signQuote(quote));

        quoteStore.put(quote.getQuoteId(), quote);
        return quote;
    }

    /**
     * Validates a previously generated quote. Returns the quote if it is valid,
     * throws an exception otherwise.
     */
    public KfeFeeQuoteResponse validateQuote(
            String quoteId,
            long amountSats,
            String destinationHash,
            String rail) {
        if (quoteId == null || quoteId.isBlank()) {
            throw new IllegalArgumentException("quoteId is required for quote validation.");
        }

        KfeFeeQuoteResponse quote = quoteStore.get(quoteId);
        if (quote == null) {
            throw new IllegalArgumentException("Quote not found: " + quoteId);
        }

        if (quote.getExpiresAt() != null && Instant.now().isAfter(quote.getExpiresAt())) {
            quoteStore.remove(quoteId);
            throw new IllegalArgumentException("Quote expired: " + quoteId);
        }

        if (quote.getAmount() == null || quote.getAmount().longValue() != amountSats) {
            throw new IllegalArgumentException("Quote amount mismatch for " + quoteId);
        }

        if (!safeEquals(quote.getDestinationHash(), destinationHash)) {
            throw new IllegalArgumentException("Quote destination mismatch for " + quoteId);
        }

        if (!safeEquals(quote.getRail(), rail)) {
            throw new IllegalArgumentException("Quote rail mismatch for " + quoteId);
        }

        String expectedSignature = signQuote(quote);
        if (!safeEquals(expectedSignature, quote.getSignature())) {
            throw new IllegalArgumentException("Quote signature invalid for " + quoteId);
        }

        return quote;
    }

    /** Signs every identity, pricing, version, and expiry field using HMAC-SHA256. */
    private String signQuote(KfeFeeQuoteResponse quote) {
        String payload = String.join("|",
                quote.getQuoteId() != null ? quote.getQuoteId() : "",
                quote.getUserId() != null ? quote.getUserId() : "",
                quote.getWalletId() != null ? quote.getWalletId() : "",
                quote.getDestinationHash() != null ? quote.getDestinationHash() : "",
                quote.getRail() != null ? quote.getRail() : "",
                quote.getAmount() != null ? quote.getAmount().toPlainString() : "",
                quote.getNetworkFeeSat() != null ? String.valueOf(quote.getNetworkFeeSat()) : "",
                quote.getServiceFeeSat() != null ? String.valueOf(quote.getServiceFeeSat()) : "",
                quote.getTotalDebitSat() != null ? String.valueOf(quote.getTotalDebitSat()) : "",
                String.valueOf(quote.getPricingPolicyVersion()),
                String.valueOf(quote.getFeeEstimateVersion()),
                quote.getExpiresAt() != null ? quote.getExpiresAt().toString() : "");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecretBytes, "HmacSHA256"));
            byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign fee quote.", e);
        }
    }

    /** Compares nullable quote bindings without treating a missing value as an empty value. */
    private static boolean safeEquals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    /** Builds fee tiers for outbound on-chain payments and returns non-applicable/client-limit estimates otherwise. */
    public Estimate estimate(
            KfeRail rail,
            KfeDirection direction,
            long requestedNetworkFeeSats) {
        Instant expiresAt = Instant.now().plusSeconds(quoteTtlSeconds);
        if (rail == KfeRail.INTERNAL || direction == KfeDirection.INTERNAL) {
            return new Estimate(0L, 0L, 0, 0L, NOT_APPLICABLE_SOURCE, estimatedVbytes, expiresAt, List.of());
        }
        if (rail != KfeRail.ONCHAIN || direction != KfeDirection.OUTBOUND) {
            return new Estimate(
                    requestedNetworkFeeSats,
                    0L,
                    0,
                    0L,
                    requestedNetworkFeeSats > 0L ? CLIENT_LIMIT_SOURCE : NOT_APPLICABLE_SOURCE,
                    estimatedVbytes,
                    expiresAt,
                    List.of());
        }

        Rate fast = rate(fastTargetBlocks, fallbackFastRate);
        Rate standard = rate(standardTargetBlocks, fallbackStandardRate);
        Rate slow = rate(slowTargetBlocks, fallbackSlowRate);

        long slowRate = slow.satPerVbyte();
        long standardRate = Math.max(standard.satPerVbyte(), slowRate);
        long fastRate = Math.max(fast.satPerVbyte(), standardRate);

        KfeFeeTierResponse fastTier = tier("FAST", fastRate, fastTargetBlocks, fast.source());
        KfeFeeTierResponse standardTier = tier(
                "STANDARD", standardRate, standardTargetBlocks, standard.source());
        KfeFeeTierResponse slowTier = tier("SLOW", slowRate, slowTargetBlocks, slow.source());

        return new Estimate(
                standardTier.networkFeeSats(),
                standardTier.feeRateSatPerVbyte(),
                standardTier.targetBlocks(),
                standardTier.estimatedSeconds(),
                standardTier.source(),
                estimatedVbytes,
                expiresAt,
                List.of(fastTier, standardTier, slowTier));
    }

    /**
     * Minimum network fee to reserve so {@code walletcreatefundedpsbt} is unlikely to exceed the
     * PSBT fee cap. Prefer the client's selected rate when present.
     */
    public long reservedFeeFloorSats(Long feeRateSatPerVbyte, Integer targetBlocks) {
        long rate;
        if (feeRateSatPerVbyte != null && feeRateSatPerVbyte > 0L) {
            rate = feeRateSatPerVbyte;
        } else {
            int blocks = targetBlocks != null && targetBlocks > 0 ? targetBlocks : standardTargetBlocks;
            long fallback = blocks <= fastTargetBlocks
                    ? fallbackFastRate
                    : blocks >= slowTargetBlocks ? fallbackSlowRate : fallbackStandardRate;
            rate = rate(blocks, fallback).satPerVbyte();
        }
        return feeSatsForRate(rate);
    }

    /** Queries Bitcoin Core for the requested target and falls back when it is absent or errors. */
    private Rate rate(int targetBlocks, long fallbackRate) {
        BitcoinCoreRpcClient bitcoinCore = bitcoinCoreProvider.getIfAvailable();
        if (bitcoinCore == null) {
            return new Rate(fallbackRate, FALLBACK_SOURCE);
        }
        try {
            return new Rate(bitcoinCore.estimateSmartFeeRateSatPerVbyte(targetBlocks), BITCOIN_CORE_SOURCE);
        } catch (RuntimeException exception) {
            return new Rate(fallbackRate, FALLBACK_SOURCE);
        }
    }

    /** Converts a rate and target into a fee tier including network-sensitive time estimate. */
    private KfeFeeTierResponse tier(String priority, long rate, int targetBlocks, String source) {
        long networkFeeSats = feeSatsForRate(rate);
        long blockSeconds = isNonMainnet(bitcoinNetwork)
                ? Math.max(expectedBlockSeconds, 1_200L)
                : expectedBlockSeconds;
        long estimatedSeconds = Math.multiplyExact((long) targetBlocks, blockSeconds);
        return new KfeFeeTierResponse(
                priority,
                rate,
                networkFeeSats,
                targetBlocks,
                estimatedSeconds,
                source);
    }

    /** Multiplies a safe minimum rate by assumed vbytes and applies the configured ceiling margin. */
    private long feeSatsForRate(long rateSatPerVbyte) {
        long safeRate = Math.max(1L, rateSatPerVbyte);
        long base = Math.multiplyExact(safeRate, estimatedVbytes);
        if (safetyMargin <= 1.0d) {
            return base;
        }
        double scaled = base * safetyMargin;
        if (scaled >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(base, (long) Math.ceil(scaled));
    }

    /** Recognizes testnet, regtest, and signet for their slower confirmation-time assumption. */
    private static boolean isNonMainnet(String network) {
        if (network == null || network.isBlank()) {
            return false;
        }
        String n = network.trim().toLowerCase();
        return n.contains("test") || n.contains("regtest") || n.contains("signet");
    }

    /** Validates a positive integer configuration parameter. */
    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    /** Validates a positive long configuration parameter. */
    private static long positive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    /** Fee-rate observation paired with the source that produced it.
     * @param satPerVbyte estimated satoshis per virtual byte
     * @param source source taxonomy used in the client response
     */
    private record Rate(long satPerVbyte, String source) {
    }

    /** Selected standard fee estimate and available response tiers.
     * @param selectedNetworkFeeSats network fee selected for pricing, in satoshis
     * @param selectedFeeRateSatPerVbyte selected rate in sat/vbyte
     * @param selectedTargetBlocks selected confirmation target
     * @param selectedEstimatedSeconds estimated time for the selected target
     * @param selectedSource source of the selected fee rate
     * @param estimatedVbytes transaction size assumption used for fee calculation
     * @param expiresAt time at which this estimate or its quote becomes stale
     * @param tiers fast, standard, and slow fee options when the rail uses on-chain fees
     */
    public record Estimate(
            long selectedNetworkFeeSats,
            long selectedFeeRateSatPerVbyte,
            int selectedTargetBlocks,
            long selectedEstimatedSeconds,
            String selectedSource,
            int estimatedVbytes,
            Instant expiresAt,
            List<KfeFeeTierResponse> tiers) {
    }
}
