package com.kerosene.kfe.paymentexecution.domain.model;

/**
 * Single binary flag outcome for forensic audit.
 *
 * @param flag   which settlement flag
 * @param pass   true = 1, false = 0
 * @param reason legacy diagnostic code/text; exception diagnostics are not inherently redacted
 */
public record FlagEvaluation(
        SettlementFlag flag,
        boolean pass,
    String reason) {

    /** Creates the passing representation of a settlement flag. */
    /** @param flag evaluated gate @param reason stable diagnostic reason @return passing flag with binary value 1 */
    public static FlagEvaluation pass(SettlementFlag flag, String reason) {
        return new FlagEvaluation(flag, true, reason);
    }

    /** Creates the failing representation of a settlement flag. */
    /** @param flag evaluated gate @param reason stable diagnostic reason @return failing flag with binary value 0 */
    public static FlagEvaluation fail(SettlementFlag flag, String reason) {
        return new FlagEvaluation(flag, false, reason);
    }

    /** Converts the boolean result to the legacy binary flag value. */
    /** @return 1 for pass or 0 for fail */
    public int binary() {
        return pass ? 1 : 0;
    }
}
