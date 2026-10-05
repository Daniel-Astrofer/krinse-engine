package com.kerosene.kfe.wallet.application.command;

import com.kerosene.kfe.wallet.domain.model.WalletKind;

/**
 * API-independent wallet creation intent.
 *
 * @param ownerId account that owns the new wallet
 * @param kind custody and wallet behavior category
 * @param name optional human-readable wallet name
 * @param label optional user-editable label
 * @param xpub optional extended public key; never a private key
 * @param descriptor optional Bitcoin output descriptor used for address discovery
 * @param fingerprint optional master-key fingerprint associated with the descriptor
 * @param derivationPath optional derivation path associated with the wallet key
 * @param initialAddress optional already-issued receive address
 * @param initialAddressDerivationPath derivation path for the supplied initial address
 * @param initialAddressDerivationIndex derivation index for the supplied initial address
 * @param initialAddressProviderReference external address-provider reference, not key material
 * @param issueInitialAddress whether creation should provision an initial receive address
 */
public record CreateWalletCommand(
        long ownerId,
        WalletKind kind,
        String name,
        String label,
        String xpub,
        String descriptor,
        String fingerprint,
        String derivationPath,
        String initialAddress,
        String initialAddressDerivationPath,
        Integer initialAddressDerivationIndex,
        String initialAddressProviderReference,
        Boolean issueInitialAddress) {
}
