package com.kerosene.kfe.liquidity.adapters.in;

import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelOperationType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record ChannelDecisionResult(
        KfeChannelOperationType operation,
        List<ChannelFlagEvaluation> evaluations) {

    public ChannelDecisionResult {
        evaluations = List.copyOf(evaluations);
    }

    public boolean passed() {
        return evaluations.stream().allMatch(ChannelFlagEvaluation::pass);
    }

    public Map<String, Object> toAuditMap() {
        Map<String, Object> flags = new LinkedHashMap<>();
        for (ChannelFlagEvaluation evaluation : evaluations) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("value", evaluation.binary());
            entry.put("reason", evaluation.reason());
            flags.put(evaluation.flag().name(), entry);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", operation.name());
        payload.put("passed", passed() ? 1 : 0);
        payload.put("flags", flags);
        payload.put(
                "failedFlags",
                evaluations.stream()
                        .filter(e -> !e.pass())
                        .map(e -> e.flag().name())
                        .collect(Collectors.toList()));
        return payload;
    }
}
