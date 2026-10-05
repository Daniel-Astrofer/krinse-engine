package com.kerosene.kfe.wallet.application.usecase;

import com.kerosene.kfe.wallet.application.command.ListWalletUtxosCommand;
import com.kerosene.kfe.wallet.application.port.in.ListWalletUtxosUseCase;
import com.kerosene.kfe.wallet.application.port.out.WalletChainPort;
import com.kerosene.kfe.wallet.application.port.out.WalletQueryPort;
import com.kerosene.kfe.wallet.application.result.WalletUtxoResult;
import com.kerosene.kfe.wallet.domain.service.WalletPolicy;

/**
 * Read-only wallet UTXO use case.
 *
 * <p>Enforces owner, active-wallet, and Bitcoin-wallet policies before chain I/O; address discovery
 * remains behind {@link WalletChainPort}.
 */
public final class ListWalletUtxosService implements ListWalletUtxosUseCase {
    /** Owner-scoped source of wallet and active-address snapshots. */
    private final WalletQueryPort wallets;
    /** Chain adapter that discovers outputs for the wallet addresses. */
    private final WalletChainPort chain;
    /** Positive derivation lookahead passed to descriptor-aware chain adapters. */
    private final int descriptorScanRange;

    /** Creates the use case and clamps descriptor lookahead to at least one address. */
    public ListWalletUtxosService(
            WalletQueryPort wallets,
            WalletChainPort chain,
            int descriptorScanRange) {
        this.wallets = wallets;
        this.chain = chain;
        this.descriptorScanRange = Math.max(1, descriptorScanRange);
    }

    /** Verifies ownership/policy, then returns the chain observations as an immutable result. */
    @Override
    public WalletUtxoResult list(ListWalletUtxosCommand command) {
        var wallet = wallets.findOwned(command.ownerId(), command.walletId())
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        WalletPolicy.requireOwnedActive(wallet, command.ownerId(), command.walletId());
        WalletPolicy.requireBitcoinWallet(wallet);
        return new WalletUtxoResult(chain.listUnspent(
                wallet,
                wallets.activeAddresses(wallet.id()),
                descriptorScanRange));
    }
}
