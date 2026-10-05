package com.kerosene.kfe.wallet.application.command;

import java.util.UUID;

public record WalletCommand(long ownerId, UUID walletId) {
}
