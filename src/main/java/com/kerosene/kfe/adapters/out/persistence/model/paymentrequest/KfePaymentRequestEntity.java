package com.kerosene.kfe.adapters.out.persistence.model.paymentrequest;

import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.paymentrequest.domain.PaymentRequestLifecyclePolicy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * JPA entity for a user-owned receiving request accepting on-chain or Lightning funds.
 *
 * <p>The entity binds a public identifier to a wallet/address and stores rail, lifecycle, expiry,
 * optional amount, behavior snapshot and settlement reference. Lifecycle callbacks maintain UTC
 * timestamps; explicit transition methods update state and its corresponding audit timestamp.</p>
 */
@Entity
@Table(name = "payment_requests", schema = "financial", indexes = {
        @Index(name = "idx_payment_requests_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_payment_requests_public_id", columnList = "public_id"),
        @Index(name = "idx_payment_requests_wallet_status", columnList = "wallet_id, status, created_at")
})
public class KfePaymentRequestEntity {
    /** Internal immutable identifier for this payment request. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();
    /** Opaque public identifier used in shareable payment-request links. */
    @Column(name = "public_id", nullable = false, unique = true, length = 48)
    private String publicId;
    /** User who owns and can manage the payment request. */
    @Column(name = "user_id", nullable = false)
    private Long userId;
    /** Wallet that receives funds for this request. */
    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;
    /** Internal receiving-address record associated with the request, when available. */
    @Column(name = "address_id")
    private UUID addressId;
    /** Primary on-chain receiving address stored for display and lookup. */
    @Column(name = "address", nullable = false, length = 128)
    private String address;

    /** BOLT11 payment request for LIGHTNING rail (may exceed address column length). */
    @Column(name = "payment_request", columnDefinition = "TEXT")
    private String paymentRequest;
    /** Lightning invoice payment hash used to observe settlement. */
    @Column(name = "payment_hash", length = 128)
    private String paymentHash;

    /** JSON array of RailDetail for multi-rail receiving payloads. */
    @Column(name = "rails_data", columnDefinition = "TEXT")
    private String railsData;
    /** Provider-side identifier used to query or cancel the generated invoice. */
    @Column(name = "provider_reference", length = 128)
    private String providerReference;
    /** Primary payment rail represented by this request; defaults to on-chain. */
    @Enumerated(EnumType.STRING)
    @Column(name = "rail", nullable = false, length = 32)
    private KfeRail rail = KfeRail.ONCHAIN;
    /** Lifecycle state of the request, initially OPEN. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KfePaymentRequestStatus status = KfePaymentRequestStatus.OPEN;
    /** Requested amount in satoshis; null represents an open-amount request. */
    @Column(name = "amount_sats")
    private Long amountSats;
    /** Short description shown with the payment request. */
    @Column(name = "description", length = 180)
    private String description;
    /** Payment memo included in the generated invoice or presentation. */
    @Column(name = "memo", length = 255)
    private String memo;
    /** Optional hint identifying the expected payer. */
    @Column(name = "payer_hint", length = 120)
    private String payerHint;
    /** KFE transaction that fulfilled this request, when paid. */
    @Column(name = "paid_transaction_id")
    private UUID paidTransactionId;
    /** UTC expiration deadline after which an open request can expire. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
    /** UTC time when the request row was created. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    /** UTC time of the most recent persisted change. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    /** UTC time when the owner hid the request from active views. */
    @Column(name = "hidden_at")
    private LocalDateTime hiddenAt;
    /** UTC time when the owner cancelled the request. */
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** JSON-serialized {@link com.kerosene.kfe.paymentrequest.domain.model.KfePaymentBehaviorContract} snapshot. */
    @Column(name = "behavior_contract", columnDefinition = "TEXT")
    private String behaviorContract;

    /** Cumulative sats received on open-amount links with ACCEPT_AND_TRACK partial policy. */
    @Column(name = "partial_payment_received")
    private Long partialPaymentReceived;

    /** Optional webhook URL for payment event delivery. */
    @Column(name = "webhook_url", length = 2048)
    private String webhookUrl;

    /** Initializes creation and update timestamps in UTC before persistence. */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    /** Refreshes the update timestamp in UTC before persistence. */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** Checks whether this open request has reached its expiry deadline using the lifecycle policy. */
    public boolean isExpired(LocalDateTime now) {
        return status == KfePaymentRequestStatus.OPEN
                && PaymentRequestLifecyclePolicy.isExpired(expiresAt, now);
    }

    /** Marks this request expired; caller is responsible for invoking it only when policy allows. */
    public void expire() {
        status = KfePaymentRequestStatus.EXPIRED;
    }

    /** Marks the request hidden and records the UTC hide time. */
    public void hide() {
        status = KfePaymentRequestStatus.HIDDEN;
        hiddenAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** Marks the request cancelled and records the UTC cancellation time. */
    public void cancel() {
        status = KfePaymentRequestStatus.CANCELLED;
        cancelledAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** Marks the request paid and links it to the transaction that fulfilled it. */
    public void markPaid(UUID transactionId) {
        status = KfePaymentRequestStatus.PAID;
        paidTransactionId = transactionId;
    }

    /** Marks the request failed; the caller records the reason in audit because no reason column exists. */
    public void markFailed(String reason) {
        status = KfePaymentRequestStatus.FAILED;
        // reason is audited by the caller; no dedicated column needed
    }

    /** Returns the stored Internal immutable identifier for this payment request. */
    public UUID getId() { return id; }
    /** Returns the stored Opaque public identifier used in shareable payment-request links. */
    public String getPublicId() { return publicId; }
    /** Updates the Opaque public identifier used in shareable payment-request links. */
    public void setPublicId(String publicId) { this.publicId = publicId; }
    /** Returns the stored User who owns and can manage the payment request. */
    public Long getUserId() { return userId; }
    /** Updates the User who owns and can manage the payment request. */
    public void setUserId(Long userId) { this.userId = userId; }
    /** Returns the stored Wallet that receives funds for this request. */
    public UUID getWalletId() { return walletId; }
    /** Updates the Wallet that receives funds for this request. */
    public void setWalletId(UUID walletId) { this.walletId = walletId; }
    /** Returns the stored Internal receiving-address record associated with the request, when available. */
    public UUID getAddressId() { return addressId; }
    /** Updates the Internal receiving-address record associated with the request, when available. */
    public void setAddressId(UUID addressId) { this.addressId = addressId; }
    /** Returns the stored Primary on-chain receiving address stored for display and lookup. */
    public String getAddress() { return address; }
    /** Updates the Primary on-chain receiving address stored for display and lookup. */
    public void setAddress(String address) { this.address = address; }
    /** Returns the stored BOLT11 payment request for the Lightning rail; may exceed ordinary address length. */
    public String getPaymentRequest() { return paymentRequest; }
    /** Updates the BOLT11 payment request for the Lightning rail; may exceed ordinary address length. */
    public void setPaymentRequest(String paymentRequest) { this.paymentRequest = paymentRequest; }
    /** Returns the stored Lightning invoice payment hash used to observe settlement. */
    public String getPaymentHash() { return paymentHash; }
    /** Updates the Lightning invoice payment hash used to observe settlement. */
    public void setPaymentHash(String paymentHash) { this.paymentHash = paymentHash; }
    /** Returns the stored Serialized JSON rail details used for multi-rail receiving payloads. */
    public String getRailsData() { return railsData; }
    /** Updates the Serialized JSON rail details used for multi-rail receiving payloads. */
    public void setRailsData(String railsData) { this.railsData = railsData; }
    /** Returns the stored Provider-side identifier used to query or cancel the generated invoice. */
    public String getProviderReference() { return providerReference; }
    /** Updates the Provider-side identifier used to query or cancel the generated invoice. */
    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }
    /** Returns the stored Primary payment rail represented by this request; defaults to on-chain. */
    public KfeRail getRail() { return rail; }
    /** Updates the Primary payment rail represented by this request; defaults to on-chain. */
    public void setRail(KfeRail rail) { this.rail = rail; }
    /** Returns the stored Lifecycle state of the request, initially OPEN. */
    public KfePaymentRequestStatus getStatus() { return status; }
    /** Updates the Lifecycle state of the request, initially OPEN. */
    public void setStatus(KfePaymentRequestStatus status) { this.status = status; }
    /** Returns the stored Requested amount in satoshis; null represents an open-amount request. */
    public Long getAmountSats() { return amountSats; }
    /** Updates the Requested amount in satoshis; null represents an open-amount request. */
    public void setAmountSats(Long amountSats) { this.amountSats = amountSats; }
    /** Returns the stored Short description shown with the payment request. */
    public String getDescription() { return description; }
    /** Updates the Short description shown with the payment request. */
    public void setDescription(String description) { this.description = description; }
    /** Returns the stored Payment memo included in the generated invoice or presentation. */
    public String getMemo() { return memo; }
    /** Updates the Payment memo included in the generated invoice or presentation. */
    public void setMemo(String memo) { this.memo = memo; }
    /** Returns the stored Optional hint identifying the expected payer. */
    public String getPayerHint() { return payerHint; }
    /** Updates the Optional hint identifying the expected payer. */
    public void setPayerHint(String payerHint) { this.payerHint = payerHint; }
    /** Returns the stored KFE transaction that fulfilled this request, when paid. */
    public UUID getPaidTransactionId() { return paidTransactionId; }
    /** Updates the KFE transaction that fulfilled this request, when paid. */
    public void setPaidTransactionId(UUID paidTransactionId) { this.paidTransactionId = paidTransactionId; }
    /** Returns the stored UTC expiration deadline after which an open request can expire. */
    public LocalDateTime getExpiresAt() { return expiresAt; }
    /** Updates the UTC expiration deadline after which an open request can expire. */
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    /** Returns the stored UTC time when the request row was created. */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** Returns the stored UTC time of the most recent persisted change. */
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    /** Returns the stored UTC time when the owner hid the request from active views. */
    public LocalDateTime getHiddenAt() { return hiddenAt; }
    /** Returns the stored UTC time when the owner cancelled the request. */
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    /** Returns the stored Serialized snapshot of the behavior contract applied to this payment request. */
    public String getBehaviorContract() { return behaviorContract; }
    /** Updates the Serialized snapshot of the behavior contract applied to this payment request. */
    public void setBehaviorContract(String behaviorContract) { this.behaviorContract = behaviorContract; }
    /** Returns the stored Cumulative satoshis received under the ACCEPT_AND_TRACK partial-payment policy. */
    public Long getPartialPaymentReceived() { return partialPaymentReceived; }
    /** Updates the Cumulative satoshis received under the ACCEPT_AND_TRACK partial-payment policy. */
    public void setPartialPaymentReceived(Long partialPaymentReceived) { this.partialPaymentReceived = partialPaymentReceived; }
    /** Returns the stored Optional destination URL for payment event delivery. */
    public String getWebhookUrl() { return webhookUrl; }
    /** Updates the Optional destination URL for payment event delivery. */
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
}
