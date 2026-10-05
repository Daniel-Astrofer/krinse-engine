package com.kerosene.kfe.audit.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit event for structured JSON logging to the "kerosene.audit.financial" stream.
 *
 * <p>NEVER log: bearer token, macaroon, full invoice, preimage, PSBT, raw transaction, PII.
 * referenceHash carries a txid or payment_hash hash, never raw values.
 *
 * @param eventId unique identifier for this audit record
 * @param eventType stable event category consumed by audit/search systems
 * @param transactionId related KFE transaction, when applicable
 * @param walletId related wallet, when applicable
 * @param principalId sanitized actor/principal identifier, when available
 * @param previousStatus prior state for transitions or a diagnostic classification
 * @param newStatus resulting state for transitions
 * @param amountSats relevant financial amount in satoshis
 * @param feeSats relevant fee in satoshis
 * @param network blockchain/network label
 * @param rail payment rail label
 * @param referenceHash one-way hash of a txid/payment hash; never the original reference
 * @param requestId request identifier for tracing
 * @param correlationId cross-service correlation identifier
 * @param occurredAt event occurrence time
 */
public record KfeAuditEvent(
        UUID eventId,
        String eventType,
        UUID transactionId,
        UUID walletId,
        String principalId,
        String previousStatus,
        String newStatus,
        long amountSats,
        long feeSats,
        String network,
        String rail,
        String referenceHash,
        String requestId,
        String correlationId,
        Instant occurredAt) {

    /**
     * Starts building an event with a random event ID and current occurrence timestamp.
     *
     * @return mutable builder for the immutable audit event
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Mutable construction helper; {@link #build()} snapshots the accumulated values. */
    public static final class Builder {
        /** Event ID generated when the builder is created unless explicitly replaced. */
        private UUID eventId = UUID.randomUUID();
        /** Required-by-convention stable event category; validated by the persistence layer. */
        private String eventType;
        /** Optional transaction related to the audit event. */
        private UUID transactionId;
        /** Optional wallet related to the audit event. */
        private UUID walletId;
        /** Optional sanitized principal/actor identifier. */
        private String principalId;
        /** Optional previous state or classification. */
        private String previousStatus;
        /** Optional resulting state. */
        private String newStatus;
        /** Associated amount in satoshis; defaults to zero. */
        private long amountSats;
        /** Associated fee in satoshis; defaults to zero. */
        private long feeSats;
        /** Optional network label. */
        private String network;
        /** Optional payment rail label. */
        private String rail;
        /** Hash of an external transaction/payment reference; must never contain the raw value. */
        private String referenceHash;
        /** Optional originating request identifier. */
        private String requestId;
        /** Optional distributed-tracing correlation identifier. */
        private String correlationId;
        /** Event time captured when the builder is created unless overridden. */
        private Instant occurredAt = Instant.now();

        /**
         * Sets the eventId audit-event property for fluent construction.
         *
         * @param eventId explicit event ID
         * @return this builder
         */
        public Builder eventId(UUID eventId) {
            this.eventId = eventId;
            return this;
        }

        /**
         * Sets the eventType audit-event property for fluent construction.
         *
         * @param eventType stable audit category
         * @return this builder
         */
        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        /**
         * Sets the transactionId audit-event property for fluent construction.
         *
         * @param transactionId related KFE transaction
         * @return this builder
         */
        public Builder transactionId(UUID transactionId) {
            this.transactionId = transactionId;
            return this;
        }

        /**
         * Sets the walletId audit-event property for fluent construction.
         *
         * @param walletId related wallet
         * @return this builder
         */
        public Builder walletId(UUID walletId) {
            this.walletId = walletId;
            return this;
        }

        /**
         * Sets the principalId audit-event property for fluent construction.
         *
         * @param principalId sanitized actor identifier
         * @return this builder
         */
        public Builder principalId(String principalId) {
            this.principalId = principalId;
            return this;
        }

        /**
         * Sets the previousStatus audit-event property for fluent construction.
         *
         * @param previousStatus prior state or diagnostic classification
         * @return this builder
         */
        public Builder previousStatus(String previousStatus) {
            this.previousStatus = previousStatus;
            return this;
        }

        /**
         * Sets the newStatus audit-event property for fluent construction.
         *
         * @param newStatus resulting state
         * @return this builder
         */
        public Builder newStatus(String newStatus) {
            this.newStatus = newStatus;
            return this;
        }

        /**
         * Sets the amountSats audit-event property for fluent construction.
         *
         * @param amountSats relevant amount in satoshis
         * @return this builder
         */
        public Builder amountSats(long amountSats) {
            this.amountSats = amountSats;
            return this;
        }

        /**
         * Sets the feeSats audit-event property for fluent construction.
         *
         * @param feeSats relevant fee in satoshis
         * @return this builder
         */
        public Builder feeSats(long feeSats) {
            this.feeSats = feeSats;
            return this;
        }

        /**
         * Sets the network audit-event property for fluent construction.
         *
         * @param network blockchain/network label
         * @return this builder
         */
        public Builder network(String network) {
            this.network = network;
            return this;
        }

        /**
         * Sets the rail audit-event property for fluent construction.
         *
         * @param rail payment rail label
         * @return this builder
         */
        public Builder rail(String rail) {
            this.rail = rail;
            return this;
        }

        /**
         * Sets the referenceHash audit-event property for fluent construction.
         *
         * @param referenceHash one-way hash, never raw external reference
         * @return this builder
         */
        public Builder referenceHash(String referenceHash) {
            this.referenceHash = referenceHash;
            return this;
        }

        /**
         * Sets the requestId audit-event property for fluent construction.
         *
         * @param requestId originating request identifier
         * @return this builder
         */
        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        /**
         * Sets the correlationId audit-event property for fluent construction.
         *
         * @param correlationId distributed tracing correlation identifier
         * @return this builder
         */
        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        /**
         * Sets the occurredAt audit-event property for fluent construction.
         *
         * @param occurredAt occurrence timestamp
         * @return this builder
         */
        public Builder occurredAt(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        /**
         * Creates an immutable event snapshot from the builder's current values.
         *
         * @return audit event populated with the current field values
         */
        public KfeAuditEvent build() {
            return new KfeAuditEvent(
                    eventId,
                    eventType,
                    transactionId,
                    walletId,
                    principalId,
                    previousStatus,
                    newStatus,
                    amountSats,
                    feeSats,
                    network,
                    rail,
                    referenceHash,
                    requestId,
                    correlationId,
                    occurredAt);
        }
    }
}
