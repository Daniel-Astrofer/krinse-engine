package com.kerosene.kfe.wallet.application.command;

import java.util.UUID;

public record ListWalletUtxosCommand(long ownerId, UUID walletId) {
}
