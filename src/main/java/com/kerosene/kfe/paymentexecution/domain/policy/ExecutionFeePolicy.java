package com.kerosene.kfe.paymentexecution.domain.policy;

import java.math.BigInteger;

/** Pure fee decisions. Applying the plan and recording its effects must share the caller's transaction. */
public final class ExecutionFeePolicy {
    private static final long SETTLEMENT_MAX_RATIO_PCT = 30L;

    public record Decision(boolean accepted, long actualFeeSats, long totalDebitSats, long releaseSats,
                           String failureCode, String message) {
        public Decision {
            if (accepted) {
                if (actualFeeSats < 0 || totalDebitSats <= 0 || releaseSats < 0
                        || failureCode != null || message != null) {
                    throw new IllegalArgumentException("Invalid accepted fee plan.");
                }
            } else if (actualFeeSats != 0 || totalDebitSats != 0 || releaseSats != 0
                    || failureCode == null || failureCode.isBlank() || message == null || message.isBlank()) {
                throw new IllegalArgumentException("Rejected fee decisions cannot carry a financial plan.");
            }
        }

        private static Decision reject(String code, String message) {
            return new Decision(false, 0, 0, 0, code, message);
        }
    }

    public record Validation(boolean valid, String reason) {
        public Validation {
            if (valid ? reason != null : reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Fee validation reason does not match its result.");
            }
        }

        private static Validation reject(String reason) { return new Validation(false, reason); }
    }

    public Decision reconcile(long reservedFeeSats, long receiverAmountSats, long totalDebitSats, long actualFeeSats) {
        if (actualFeeSats < 0) {
            return Decision.reject("INVALID_ACTUAL_FEE", "Provider returned a negative network fee.");
        }
        if (reservedFeeSats < 0 || receiverAmountSats <= 0 || totalDebitSats <= 0) {
            return Decision.reject("INVALID_FEE_SNAPSHOT", "Network fee reconciliation requires a valid monetary snapshot.");
        }
        if (actualFeeSats > reservedFeeSats) {
            return Decision.reject("ACTUAL_FEE_EXCEEDS_RESERVED",
                    "Actual fee " + actualFeeSats + " exceeds reserved " + reservedFeeSats
                            + " by " + (actualFeeSats - reservedFeeSats) + " sats. New authorization required.");
        }
        if (exceedsPercentage(actualFeeSats, receiverAmountSats, SETTLEMENT_MAX_RATIO_PCT)) {
            return Decision.reject("FEE_EXCEEDS_MAX_RATIO", "Fee " + actualFeeSats + " exceeds 30% of amount.");
        }
        final long debitWithoutNetworkFee;
        final long reconciledTotalDebit;
        try {
            debitWithoutNetworkFee = Math.subtractExact(totalDebitSats, reservedFeeSats);
            reconciledTotalDebit = Math.addExact(debitWithoutNetworkFee, actualFeeSats);
        } catch (ArithmeticException exception) {
            return Decision.reject("FEE_RECONCILIATION_OVERFLOW",
                    "Network fee reconciliation overflowed the transaction amount.");
        }
        if (debitWithoutNetworkFee <= 0 || reconciledTotalDebit <= 0 || reconciledTotalDebit > totalDebitSats) {
            return Decision.reject("INVALID_RECONCILED_DEBIT", "Network fee reconciliation produced an invalid total debit.");
        }
        return new Decision(true, actualFeeSats, reconciledTotalDebit, totalDebitSats - reconciledTotalDebit, null, null);
    }

    /** No sat/vB check is possible here without the transaction's virtual size. Zero caps are disabled. */
    public Validation validateBeforeBroadcast(long estimated, long reserved, long amount, long maxAbsolute, long maxRatioPct) {
        if (estimated < 0) {
            return Validation.reject("Estimated fee is negative: " + estimated);
        }
        if (reserved < 0 || amount <= 0 || maxAbsolute < 0 || maxRatioPct < 0) {
            return Validation.reject("Fee validation requires a valid monetary snapshot and non-negative limits.");
        }
        if (estimated > reserved) {
            return Validation.reject("Fee " + estimated + " exceeds reserved " + reserved
                    + " by " + (estimated - reserved) + " sats.");
        }
        if (maxAbsolute > 0 && estimated > maxAbsolute) {
            return Validation.reject("Fee " + estimated + " exceeds absolute max " + maxAbsolute + ".");
        }
        if (maxRatioPct > 0 && exceedsPercentage(estimated, amount, maxRatioPct)) {
            return Validation.reject("Fee " + estimated + " exceeds " + maxRatioPct + "% of amount " + amount + ".");
        }
        return new Validation(true, null);
    }

    private static boolean exceedsPercentage(long fee, long amount, long percent) {
        BigInteger limit = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(percent)).divide(BigInteger.valueOf(100));
        return BigInteger.valueOf(fee).compareTo(limit) > 0;
    }
}
