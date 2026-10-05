package com.kerosene.kfe.paymentexecution.domain.settlement;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import static com.kerosene.kfe.paymentexecution.domain.settlement.InboundSettlementDecision.Outcome;

/** Pure policy that prevents an observation for one execution from settling another. */
public final class InboundSettlementBindingPolicy {

    private static final Pattern BITCOIN_TXID = Pattern.compile("^[0-9a-fA-F]{64}$");

    public InboundSettlementDecision decide(
            InboundSettlementBinding binding,
            InboundSettlementEvidence evidence) {
        if (binding == null || evidence == null) {
            return reject("INBOUND_BINDING_INVALID");
        }
        if (binding.proofTransactionId() == null
                || !binding.proofTransactionId().equals(binding.outboxTransactionId())) {
            return reject("INBOUND_TRANSACTION_BINDING_MISMATCH");
        }

        String rail = normalized(binding.rail());
        if (!"ONCHAIN".equals(rail) && !"LIGHTNING".equals(rail)) {
            return reject("INBOUND_RAIL_INVALID");
        }
        if (!Objects.equals(rail + "_INBOUND", normalized(binding.outboxOperation()))) {
            return reject("INBOUND_OPERATION_MISMATCH");
        }
        if (!"INBOUND".equals(normalized(binding.direction()))) {
            return reject("INBOUND_DIRECTION_MISMATCH");
        }
        if (binding.transactionUserId() <= 0L
                || binding.destinationWalletId() == null
                || binding.destinationWalletUserId() == null
                || binding.destinationWalletUserId() != binding.transactionUserId()) {
            return reject("INBOUND_DESTINATION_OWNERSHIP_MISMATCH");
        }
        if (isBlank(evidence.providerReference()) || isBlank(evidence.networkReference())) {
            return reject("INBOUND_REFERENCE_INVALID");
        }
        String outboxStatus = normalized(binding.outboxStatus());
        if (!"UNKNOWN".equals(outboxStatus) && !"DISPATCHED".equals(outboxStatus)) {
            return reject("INBOUND_OUTBOX_STATUS_INVALID");
        }
        String status = normalized(binding.transactionStatus());
        if (binding.providerReferenceOwnedByOtherSettledTransaction()) {
            return reconcile("INBOUND_PROVIDER_REFERENCE_CONFLICT");
        }
        if (referenceMismatch(binding.persistedOutboxProviderReference(), evidence.providerReference())
                || referenceMismatch(binding.persistedProviderReference(), evidence.providerReference())
                || referenceMismatch(binding.persistedNetworkReference(), evidence.networkReference())) {
            return "SETTLED".equals(status)
                    ? reconcile("INBOUND_SETTLED_REFERENCE_MISMATCH")
                    : reject("INBOUND_REFERENCE_MISMATCH");
        }
        if (binding.grossAmountSats() <= 0L || evidence.observedAmountSats() <= 0L) {
            return reject("INBOUND_AMOUNT_INVALID");
        }
        if (evidence.observedAmountSats() < binding.grossAmountSats()) {
            return reconcile("INBOUND_AMOUNT_BELOW_EXPECTED");
        }
        long creditSats = binding.receiverAmountSats() > 0L
                ? binding.receiverAmountSats()
                : evidence.observedAmountSats();
        if (creditSats <= 0L
                || creditSats > evidence.observedAmountSats()
                || creditSats > binding.grossAmountSats()) {
            return reject("INBOUND_CREDIT_AMOUNT_INVALID");
        }
        if ("ONCHAIN".equals(rail)) {
            if (!BITCOIN_TXID.matcher(evidence.networkReference().trim()).matches()) {
                return reject("INBOUND_NETWORK_REFERENCE_INVALID");
            }
            if (evidence.requiredOnchainConfirmations() < 1
                    || evidence.confirmations() < evidence.requiredOnchainConfirmations()) {
                return reject("INBOUND_FINALITY_NOT_REACHED");
            }
        } else if (evidence.confirmations() <= 0) {
            return reject("INBOUND_LIGHTNING_NOT_CONFIRMED");
        }

        if ("SETTLED".equals(status)) {
            if (sameReference(binding.persistedProviderReference(), evidence.providerReference())
                    && sameReference(binding.persistedNetworkReference(), evidence.networkReference())) {
                String code = "DISPATCHED".equals(outboxStatus)
                        ? "INBOUND_ALREADY_DISPATCHED"
                        : "INBOUND_ALREADY_SETTLED";
                return new InboundSettlementDecision(Outcome.IDEMPOTENT, code);
            }
            return reconcile("INBOUND_SETTLED_REFERENCE_MISMATCH");
        }
        if (!"UNKNOWN".equals(outboxStatus)) {
            return reject("INBOUND_OUTBOX_STATUS_INVALID");
        }
        if (!"EXECUTING".equals(status) && !"REQUIRES_RECONCILIATION".equals(status)) {
            return reject("INBOUND_STATUS_INVALID");
        }
        return new InboundSettlementDecision(Outcome.SETTLE, "INBOUND_SETTLEMENT_ALLOWED");
    }

    private static InboundSettlementDecision reject(String code) {
        return new InboundSettlementDecision(Outcome.REJECT, code);
    }

    private static InboundSettlementDecision reconcile(String code) {
        return new InboundSettlementDecision(Outcome.RECONCILE, code);
    }

    private static boolean sameReference(String persisted, String observed) {
        return !isBlank(persisted) && persisted.trim().equals(observed.trim());
    }

    private static boolean referenceMismatch(String persisted, String observed) {
        return !isBlank(persisted) && !sameReference(persisted, observed);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
