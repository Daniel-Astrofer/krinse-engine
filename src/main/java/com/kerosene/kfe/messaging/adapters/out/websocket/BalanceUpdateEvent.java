package com.kerosene.kfe.messaging.adapters.out.websocket;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Realtime balance snapshot. Legacy clients only read {@code newBalance}/{@code amount}/{@code context}.
 * New fields enable dual-ledger UIs (available vs observed) without overwriting the wrong bucket.
 */
public class BalanceUpdateEvent {
    /** Stable identifier of the wallet whose balance snapshot changed. */
    private String walletId;
    /** Human-readable wallet label included for clients that render a balance notification directly. */
    private String walletName;
    /** Owner identifier used by consumers to associate the event with the authenticated user's wallets. */
    private Long userId;
    /** Legacy aggregate balance value retained for clients that have not adopted satoshi buckets. */
    private BigDecimal newBalance;
    /** Legacy amount associated with the triggering balance mutation, in the legacy API unit. */
    private BigDecimal amount;
    /** Origin or business context describing why the balance update was emitted. */
    private String context;
    /** UTC wall-clock time at which the event was constructed, unless supplied by deserialization. */
    private LocalDateTime timestamp;

    /** Wallet kind: INTERNAL, CUSTODIAL_ONCHAIN, WATCH_ONLY, … */
    private String kind;
    /** Spendable wallet amount in satoshis after the mutation. */
    private Long availableSats;
    /** Amount reserved or otherwise locked against spending, in satoshis. */
    private Long lockedSats;
    /** Amount awaiting final settlement or confirmation, in satoshis. */
    private Long pendingSats;
    /** Chain-observed amount in satoshis, which may not yet be spendable. */
    private Long observedSats;
    /** Primary display sats for this kind (cold=observed; else available). */
    private Long primarySats;
    /** Bucket that primarily changed: AVAILABLE, LOCKED, OBSERVED, PRIMARY. */
    private String bucket;

    /**
     * Creates the legacy-compatible scalar balance event and stamps its creation time in UTC.
     *
     * @param walletId stable wallet identifier
     * @param walletName display label for the wallet
     * @param userId wallet owner's identifier
     * @param newBalance legacy aggregate balance after the mutation
     * @param amount legacy mutation amount
     * @param context business context or mutation origin
     */
    public BalanceUpdateEvent(String walletId, String walletName, Long userId, BigDecimal newBalance,
            BigDecimal amount, String context) {
        this.walletId = walletId;
        this.walletName = walletName;
        this.userId = userId;
        this.newBalance = newBalance;
        this.amount = amount;
        this.context = context;
        this.timestamp = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /**
     * Creates a balance event with both legacy scalar values and explicit ledger bucket snapshots.
     * Delegates common identity/time initialization to the legacy constructor.
     *
     * @param walletId stable wallet identifier
     * @param walletName display label for the wallet
     * @param userId wallet owner's identifier
     * @param newBalance legacy aggregate balance after the mutation
     * @param amount legacy mutation amount
     * @param context business context or mutation origin
     * @param kind wallet classification controlling how clients interpret its balances
     * @param availableSats spendable amount after the mutation, in satoshis
     * @param lockedSats reserved or locked amount in satoshis
     * @param pendingSats unsettled amount in satoshis
     * @param observedSats chain-observed amount in satoshis
     * @param primarySats amount clients should emphasize for this wallet kind
     * @param bucket principal balance bucket changed by the mutation
     */
    public BalanceUpdateEvent(
            String walletId,
            String walletName,
            Long userId,
            BigDecimal newBalance,
            BigDecimal amount,
            String context,
            String kind,
            Long availableSats,
            Long lockedSats,
            Long pendingSats,
            Long observedSats,
            Long primarySats,
            String bucket) {
        this(walletId, walletName, userId, newBalance, amount, context);
        this.kind = kind;
        this.availableSats = availableSats;
        this.lockedSats = lockedSats;
        this.pendingSats = pendingSats;
        this.observedSats = observedSats;
        this.primarySats = primarySats;
        this.bucket = bucket;
    }

    /** @return stable identifier of the changed wallet */
    public String getWalletId() {
        return walletId;
    }

    /** @param walletId stable identifier of the changed wallet */
    public void setWalletId(String walletId) {
        this.walletId = walletId;
    }

    /** @return display label of the wallet */
    public String getWalletName() {
        return walletName;
    }

    /** @param walletName display label of the wallet */
    public void setWalletName(String walletName) {
        this.walletName = walletName;
    }

    /** @return wallet owner's identifier */
    public Long getUserId() {
        return userId;
    }

    /** @param userId wallet owner's identifier */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /** @return legacy aggregate balance after the mutation */
    public BigDecimal getNewBalance() {
        return newBalance;
    }

    /** @param newBalance legacy aggregate balance after the mutation */
    public void setNewBalance(BigDecimal newBalance) {
        this.newBalance = newBalance;
    }

    /** @return legacy amount associated with the triggering mutation */
    public BigDecimal getAmount() {
        return amount;
    }

    /** @param amount legacy amount associated with the triggering mutation */
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    /** @return business context or origin of the balance change */
    public String getContext() {
        return context;
    }

    /** @param context business context or origin of the balance change */
    public void setContext(String context) {
        this.context = context;
    }

    /** @return event construction time in UTC, unless restored from a serialized event */
    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    /** @param timestamp event time to restore or explicitly assign */
    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    /** @return wallet classification used to interpret bucket values */
    public String getKind() {
        return kind;
    }

    /** @param kind wallet classification used to interpret bucket values */
    public void setKind(String kind) {
        this.kind = kind;
    }

    /** @return spendable wallet amount in satoshis */
    public Long getAvailableSats() {
        return availableSats;
    }

    /** @param availableSats spendable wallet amount in satoshis */
    public void setAvailableSats(Long availableSats) {
        this.availableSats = availableSats;
    }

    /** @return amount reserved or locked against spending, in satoshis */
    public Long getLockedSats() {
        return lockedSats;
    }

    /** @param lockedSats amount reserved or locked against spending, in satoshis */
    public void setLockedSats(Long lockedSats) {
        this.lockedSats = lockedSats;
    }

    /** @return amount awaiting settlement or confirmation, in satoshis */
    public Long getPendingSats() {
        return pendingSats;
    }

    /** @param pendingSats amount awaiting settlement or confirmation, in satoshis */
    public void setPendingSats(Long pendingSats) {
        this.pendingSats = pendingSats;
    }

    /** @return chain-observed amount that may not yet be spendable, in satoshis */
    public Long getObservedSats() {
        return observedSats;
    }

    /** @param observedSats chain-observed amount that may not yet be spendable, in satoshis */
    public void setObservedSats(Long observedSats) {
        this.observedSats = observedSats;
    }

    /** @return primary display amount selected for this wallet kind, in satoshis */
    public Long getPrimarySats() {
        return primarySats;
    }

    /** @param primarySats primary display amount selected for this wallet kind, in satoshis */
    public void setPrimarySats(Long primarySats) {
        this.primarySats = primarySats;
    }

    /** @return principal ledger bucket changed by the mutation */
    public String getBucket() {
        return bucket;
    }

    /** @param bucket principal ledger bucket changed by the mutation */
    public void setBucket(String bucket) {
        this.bucket = bucket;
    }
}
