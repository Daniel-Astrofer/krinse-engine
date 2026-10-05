package com.kerosene.kfe.wallet.application.command;

import com.kerosene.kfe.wallet.domain.model.ColdPsbtRequest;

import java.util.UUID;

/**
 * API-independent request to prepare an offline-signing wallet payment.
 *
 * @param ownerId account that owns and authorizes the wallet operation
 * @param walletId target wallet; ownership and custody type are checked by the use case
 * @param request destination, amount, fee policy, and optional input restrictions
 * @param approvalFactor sensitive account factor verified before chain input selection
 */
public record CreateColdPsbtCommand(
        long ownerId,
        UUID walletId,
        ColdPsbtRequest request,
        String approvalFactor) {
}
