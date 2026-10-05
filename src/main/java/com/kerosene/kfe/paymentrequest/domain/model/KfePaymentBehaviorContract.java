package com.kerosene.kfe.paymentrequest.domain.model;

/**
 * Service-level contract that documents and enforces payment request behavior.
 * Acts as a configuration snapshot per payment request — immutable after creation.
 *
 * <p>Each persisted payment request carries one instance of this contract,
 * serialized as a JSON column ({@code behavior_contract}) for auditability.
 */
public class KfePaymentBehaviorContract {

    /** Defines whether successful payment consumes a request link or leaves it available for reuse. */
    public enum LinkType {
        /** First payment marks the link as PAID; subsequent payments rejected. */
        SINGLE_USE,
        /** Link stays OPEN after each payment; payer can reuse. */
        REUSABLE
    }

    /** Defines how received amounts below a fixed requested amount are treated. */
    public enum PartialPaymentPolicy {
        /** Accept partial payments and track cumulative received. */
        ACCEPT_AND_TRACK,
        /** Refuse any payment below the declared amount. */
        REJECT,
        /** Hold partial payment but do not credit until full amount arrives. */
        HOLD_FOR_FULL
    }

    /** Defines whether an amount above the declared request is fully credited or separated for handling. */
    public enum OverpaymentPolicy {
        /** Credit the full received amount (current behavior). */
        CREDIT_FULL,
        /** Credit declared amount, refund excess to sender. */
        REFUND_EXCESS,
        /** Credit declared amount, hold excess for manual review. */
        HOLD_EXCESS
    }

    /** Defines how confirmed rail settlement is treated when KFE's request expiry time has passed. */
    public enum ExpiredPaymentPolicy {
        /** Refuse any payment after expiry. */
        REJECT,
        /** Accept if the invoice settled within a short grace period after expiry (monitor reconciliation path). */
        ACCEPT_WITHIN_GRACE_PERIOD,
        /** Always accept if the invoice is confirmed settled on the rail, regardless of KFE clock. */
        ALWAYS_ACCEPT_IF_CONFIRMED
    }

    /** Whether one successful payment consumes the link or permits subsequent payments. */
    private LinkType linkType;
    /** Policy for inbound amounts below a fixed target; interpretation is applied by settlement consumers. */
    private PartialPaymentPolicy partialPaymentPolicy;
    /** Policy for inbound amounts above a fixed target; interpretation is applied by settlement consumers. */
    private OverpaymentPolicy overpaymentPolicy;
    /** Policy for provider-confirmed payment evidence observed after local request expiry. */
    private ExpiredPaymentPolicy expiredPaymentPolicy;

    /**
     * Creates the conservative fixed-link defaults: single-use, reject partial, credit full overpayment,
     * and reject settlement after expiry.
     */
    public KfePaymentBehaviorContract() {
        this.linkType = LinkType.SINGLE_USE;
        this.partialPaymentPolicy = PartialPaymentPolicy.REJECT;
        this.overpaymentPolicy = OverpaymentPolicy.CREDIT_FULL;
        this.expiredPaymentPolicy = ExpiredPaymentPolicy.REJECT;
    }

    /**
     * Creates a policy snapshot, replacing each null option with its conservative default.
     *
     * @param linkType single-use or reusable link semantics
     * @param partialPaymentPolicy treatment for underpayments
     * @param overpaymentPolicy treatment for amounts above the declared target
     * @param expiredPaymentPolicy treatment for confirmed settlements after expiry
     */
    public KfePaymentBehaviorContract(
            LinkType linkType,
            PartialPaymentPolicy partialPaymentPolicy,
            OverpaymentPolicy overpaymentPolicy,
            ExpiredPaymentPolicy expiredPaymentPolicy) {
        this.linkType = linkType != null ? linkType : LinkType.SINGLE_USE;
        this.partialPaymentPolicy = partialPaymentPolicy != null ? partialPaymentPolicy : PartialPaymentPolicy.REJECT;
        this.overpaymentPolicy = overpaymentPolicy != null ? overpaymentPolicy : OverpaymentPolicy.CREDIT_FULL;
        this.expiredPaymentPolicy = expiredPaymentPolicy != null ? expiredPaymentPolicy : ExpiredPaymentPolicy.REJECT;
    }

    /**
     * Default contract for fixed-amount payment links.
     * SINGLE_USE, REJECT partial, CREDIT_FULL overpayment, REJECT after expiry.
     *
     * @return a new fixed-amount behavior snapshot intended to remain unchanged after request creation
     */
    public static KfePaymentBehaviorContract forFixedAmount() {
        return new KfePaymentBehaviorContract(
                LinkType.SINGLE_USE, PartialPaymentPolicy.REJECT,
                OverpaymentPolicy.CREDIT_FULL, ExpiredPaymentPolicy.REJECT);
    }

    /**
     * Default contract for open-amount payment links.
     * SINGLE_USE, ACCEPT_AND_TRACK partials, CREDIT_FULL overpayment, ACCEPT_WITHIN_GRACE_PERIOD for expiry.
     *
     * @return a new open-amount behavior snapshot
     */
    public static KfePaymentBehaviorContract forOpenAmount() {
        return new KfePaymentBehaviorContract(
                LinkType.SINGLE_USE, PartialPaymentPolicy.ACCEPT_AND_TRACK,
                OverpaymentPolicy.CREDIT_FULL, ExpiredPaymentPolicy.ACCEPT_WITHIN_GRACE_PERIOD);
    }

    /**
     * Default contract for reusable payment links (e.g., tip jars, donation pages).
     * REUSABLE, ACCEPT_AND_TRACK partials, CREDIT_FULL overpayment, ALWAYS_ACCEPT_IF_CONFIRMED for expiry.
     *
     * @return a new reusable-link behavior snapshot
     */
    public static KfePaymentBehaviorContract forReusable() {
        return new KfePaymentBehaviorContract(
                LinkType.REUSABLE, PartialPaymentPolicy.ACCEPT_AND_TRACK,
                OverpaymentPolicy.CREDIT_FULL, ExpiredPaymentPolicy.ALWAYS_ACCEPT_IF_CONFIRMED);
    }

    // --- Getters / setters (for JSON serialization) ---

    /** @return configured single-use or reusable link type */
    public LinkType getLinkType() { return linkType; }
    /** @param linkType link lifecycle policy restored or updated by JSON serialization */
    public void setLinkType(LinkType linkType) { this.linkType = linkType; }

    /** @return configured underpayment policy */
    public PartialPaymentPolicy getPartialPaymentPolicy() { return partialPaymentPolicy; }
    /** @param partialPaymentPolicy policy for payments below the declared amount */
    public void setPartialPaymentPolicy(PartialPaymentPolicy partialPaymentPolicy) { this.partialPaymentPolicy = partialPaymentPolicy; }

    /** @return configured overpayment policy */
    public OverpaymentPolicy getOverpaymentPolicy() { return overpaymentPolicy; }
    /** @param overpaymentPolicy policy for amounts above the declared amount */
    public void setOverpaymentPolicy(OverpaymentPolicy overpaymentPolicy) { this.overpaymentPolicy = overpaymentPolicy; }

    /** @return configured post-expiry settlement policy */
    public ExpiredPaymentPolicy getExpiredPaymentPolicy() { return expiredPaymentPolicy; }
    /** @param expiredPaymentPolicy policy for provider-confirmed payments observed after expiry */
    public void setExpiredPaymentPolicy(ExpiredPaymentPolicy expiredPaymentPolicy) { this.expiredPaymentPolicy = expiredPaymentPolicy; }

    /** @return true when the first successful payment consumes this link */
    public boolean isSingleUse() { return linkType == LinkType.SINGLE_USE; }
    /** @return true when the link remains reusable after successful payments */
    public boolean isReusable() { return linkType == LinkType.REUSABLE; }
    /** @return true when an underpayment must be rejected */
    public boolean rejectsPartial() { return partialPaymentPolicy == PartialPaymentPolicy.REJECT; }
    /** @return true when partial receipts are accepted and accumulated toward the target */
    public boolean acceptsTrackedPartial() { return partialPaymentPolicy == PartialPaymentPolicy.ACCEPT_AND_TRACK; }
    /** @return true when the entire observed overpayment is credited */
    public boolean creditsFullOverpayment() { return overpaymentPolicy == OverpaymentPolicy.CREDIT_FULL; }
    /** @return true when confirmed settlement may be reconciled within a configured grace period */
    public boolean acceptsWithinGracePeriod() { return expiredPaymentPolicy == ExpiredPaymentPolicy.ACCEPT_WITHIN_GRACE_PERIOD; }
    /** @return true when any rail-confirmed settlement is accepted despite local expiry */
    public boolean alwaysAcceptsIfConfirmed() { return expiredPaymentPolicy == ExpiredPaymentPolicy.ALWAYS_ACCEPT_IF_CONFIRMED; }
}
