package com.kerosene.kfe.paymentexecution.domain.policy;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionIntentBinding;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.util.Locale;
import java.util.Objects;

/** Compares execution intent; does not establish message authenticity or accounting conservation. */
public final class ExecutionIntentBindingPolicy {

    public boolean matches(String operation, ExecutionIntentBinding authorized, ExecutionIntentBinding message) {
        if (!eligible(authorized) || !eligible(message)) {
            return false;
        }
        String normalizedOperation = operation == null ? "" : operation.trim().toUpperCase(Locale.ROOT);
        if (!normalizedOperation.equals(authorized.rail().name() + "_" + authorized.direction().name())) {
            return false;
        }
        return authorized.transactionId().equals(message.transactionId())
                && authorized.userId() == message.userId()
                && authorized.idempotencyKey().equals(message.idempotencyKey())
                && authorized.rail() == message.rail()
                && authorized.direction() == message.direction()
                && authorized.sourceWalletId().equals(message.sourceWalletId())
                && Objects.equals(authorized.destinationWalletId(), message.destinationWalletId())
                && authorized.amountSats() == message.amountSats()
                && authorized.networkFeeSats() == message.networkFeeSats()
                && authorized.totalDebitSats() == message.totalDebitSats()
                && Objects.equals(normalizedText(authorized.externalReference()), normalizedText(message.externalReference()))
                && Objects.equals(normalizedText(authorized.memo()), normalizedText(message.memo()))
                && authorized.quorumProposalHash().equals(message.quorumProposalHash());
    }

    private static boolean eligible(ExecutionIntentBinding binding) {
        return binding != null
                && binding.transactionId() != null
                && binding.userId() > 0L
                && nonBlank(binding.idempotencyKey())
                && (binding.rail() == PaymentRail.ONCHAIN || binding.rail() == PaymentRail.LIGHTNING)
                && binding.direction() == PaymentDirection.OUTBOUND
                && binding.sourceWalletId() != null
                && binding.amountSats() > 0L
                && binding.networkFeeSats() >= 0L
                && binding.totalDebitSats() > binding.networkFeeSats()
                && normalizedText(binding.externalReference()) != null
                && nonBlank(binding.quorumProposalHash());
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizedText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
