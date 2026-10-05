package com.kerosene.kfe.adapters.in.http.dto.pricing;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Mutable JSON response carrying a fee quote bound to a user, wallet, destination, rail, and expiry.
 * The signature allows downstream transaction submission to verify that quoted terms were not changed.
 */
public class KfeFeeQuoteResponse {

    /** Opaque identifier used to bind transaction submission to this quote. */
    private String quoteId;
    /** User for whom the quote was calculated. */
    private String userId;
    /** Source wallet whose funds and policy were used for pricing. */
    private String walletId;
    /** Non-reversible digest of the destination used to scope the quote. */
    private String destinationHash;
    /** Payment rail used to estimate execution costs. */
    private String rail;
    /** Quoted transfer amount in the API's decimal monetary representation. */
    private BigDecimal amount;
    /** Estimated network or provider fee in satoshis. */
    private Long networkFeeSat;
    /** Platform service fee in satoshis. */
    private Long serviceFeeSat;
    /** Total amount to debit, including quoted fees, in satoshis. */
    private Long totalDebitSat;
    /** Version of the fee policy used to calculate the quote. */
    private int pricingPolicyVersion;
    /** Version of the underlying fee estimator used for the quote. */
    private int feeEstimateVersion;
    /** Instant after which the quote must no longer be accepted. */
    private Instant expiresAt;
    /** Integrity signature over the quote's bound values and versions. */
    private String signature;

    /** Returns the quote identifier used for quote-bound execution. */
    public String getQuoteId() {
        return quoteId;
    }

    /** Sets the quote identifier used for quote-bound execution. */
    public void setQuoteId(String quoteId) {
        this.quoteId = quoteId;
    }

    /** Returns the user ID to which the quote is scoped. */
    public String getUserId() {
        return userId;
    }

    /** Sets the user ID to which the quote is scoped. */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /** Returns the source wallet ID used for fee and balance calculations. */
    public String getWalletId() {
        return walletId;
    }

    /** Sets the source wallet ID used for fee and balance calculations. */
    public void setWalletId(String walletId) {
        this.walletId = walletId;
    }

    /** Returns the destination binding hash without exposing the raw destination. */
    public String getDestinationHash() {
        return destinationHash;
    }

    /** Sets the destination binding hash without exposing the raw destination. */
    public void setDestinationHash(String destinationHash) {
        this.destinationHash = destinationHash;
    }

    /** Returns the payment rail used by the quote. */
    public String getRail() {
        return rail;
    }

    /** Sets the payment rail used by the quote. */
    public void setRail(String rail) {
        this.rail = rail;
    }

    /** Returns the quoted transfer amount. */
    public BigDecimal getAmount() {
        return amount;
    }

    /** Sets the quoted transfer amount. */
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    /** Returns the estimated network fee in satoshis. */
    public Long getNetworkFeeSat() {
        return networkFeeSat;
    }

    /** Sets the estimated network fee in satoshis. */
    public void setNetworkFeeSat(Long networkFeeSat) {
        this.networkFeeSat = networkFeeSat;
    }

    /** Returns the platform service fee in satoshis. */
    public Long getServiceFeeSat() {
        return serviceFeeSat;
    }

    /** Sets the platform service fee in satoshis. */
    public void setServiceFeeSat(Long serviceFeeSat) {
        this.serviceFeeSat = serviceFeeSat;
    }

    /** Returns the total debit including fees, in satoshis. */
    public Long getTotalDebitSat() {
        return totalDebitSat;
    }

    /** Sets the total debit including fees, in satoshis. */
    public void setTotalDebitSat(Long totalDebitSat) {
        this.totalDebitSat = totalDebitSat;
    }

    /** Returns the pricing policy version used to compute this quote. */
    public int getPricingPolicyVersion() {
        return pricingPolicyVersion;
    }

    /** Sets the pricing policy version used to compute this quote. */
    public void setPricingPolicyVersion(int pricingPolicyVersion) {
        this.pricingPolicyVersion = pricingPolicyVersion;
    }

    /** Returns the estimator version used to compute network fees. */
    public int getFeeEstimateVersion() {
        return feeEstimateVersion;
    }

    /** Sets the estimator version used to compute network fees. */
    public void setFeeEstimateVersion(int feeEstimateVersion) {
        this.feeEstimateVersion = feeEstimateVersion;
    }

    /** Returns the quote expiry instant. */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** Sets the quote expiry instant. */
    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    /** Returns the signature that authenticates the quote terms. */
    public String getSignature() {
        return signature;
    }

    /** Sets the signature that authenticates the quote terms. */
    public void setSignature(String signature) {
        this.signature = signature;
    }
}
