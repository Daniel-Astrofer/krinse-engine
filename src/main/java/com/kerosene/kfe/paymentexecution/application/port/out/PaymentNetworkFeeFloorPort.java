package com.kerosene.kfe.paymentexecution.application.port.out;

/** Provides the authoritative fee reserve floor for an outbound on-chain payment. */
public interface PaymentNetworkFeeFloorPort {

    /** Computes the authoritative minimum reserve for an outbound on-chain payment. */
    /** @param feeRateSatPerVbyte optional requested fee rate @param feeTargetBlocks optional confirmation target @return minimum fee reserve in integer satoshis */
    long minimumReserve(Long feeRateSatPerVbyte, Integer feeTargetBlocks);
}
