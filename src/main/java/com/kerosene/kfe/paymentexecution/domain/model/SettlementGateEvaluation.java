package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Full AND-product of settlement flags plus quorum acknowledgement metadata when present.
 * @param evaluations ordered per-flag pass/fail results
 * @param quorumAckCount accepted consensus acknowledgements, if evaluated
 * @param quorumHealthyNodes healthy consensus members, if evaluated
 */
public record SettlementGateEvaluation(
        List<FlagEvaluation> evaluations,
        int quorumAckCount,
        int quorumHealthyNodes) {

    /** Copies evaluations to an immutable list for stable decision reporting. */
    public SettlementGateEvaluation {
        evaluations = List.copyOf(evaluations);
    }

    /** Returns the logical AND of all flag results. */
    /** @return true only when every evaluated gate passed */
    public boolean passed() {
        return evaluations.stream().allMatch(FlagEvaluation::pass);
    }

    /** Lists every gate that failed in the original evaluation order. */
    /** @return failed settlement flags */
    public List<SettlementFlag> failedFlags() {
        return evaluations.stream()
                .filter(evaluation -> !evaluation.pass())
                .map(FlagEvaluation::flag)
                .toList();
    }

    /** Indexes evaluations by flag and returns an immutable view. */
    /** @return unmodifiable map from settlement flag to its result */
    public Map<SettlementFlag, FlagEvaluation> byFlag() {
        Map<SettlementFlag, FlagEvaluation> map = new EnumMap<>(SettlementFlag.class);
        for (FlagEvaluation evaluation : evaluations) {
            map.put(evaluation.flag(), evaluation);
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Compact audit payload: each flag → 0/1 and reason.
     * @return serialized audit map including aggregate decision, failed flags, and quorum counts
     */
    public Map<String, Object> toAuditPayload() {
        Map<String, Object> flags = new LinkedHashMap<>();
        for (FlagEvaluation evaluation : evaluations) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("value", evaluation.binary());
            entry.put("reason", evaluation.reason());
            flags.put(evaluation.flag().name(), entry);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("passed", passed() ? 1 : 0);
        payload.put("flags", flags);
        payload.put(
                "failedFlags",
                failedFlags().stream().map(Enum::name).collect(Collectors.toList()));
        payload.put("quorumAckCount", quorumAckCount);
        payload.put("quorumHealthyNodes", quorumHealthyNodes);
        return payload;
    }
}
