package com.kerosene.kfe.liquidity.adapters.in;

public record ChannelFlagEvaluation(
        ChannelDecisionFlag flag,
        boolean pass,
        String reason) {

    public static ChannelFlagEvaluation pass(ChannelDecisionFlag flag, String reason) {
        return new ChannelFlagEvaluation(flag, true, reason);
    }

    public static ChannelFlagEvaluation fail(ChannelDecisionFlag flag, String reason) {
        return new ChannelFlagEvaluation(flag, false, reason);
    }

    public int binary() {
        return pass ? 1 : 0;
    }
}
