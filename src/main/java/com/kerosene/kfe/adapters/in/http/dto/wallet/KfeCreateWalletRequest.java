package com.kerosene.kfe.adapters.in.http.dto.wallet;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletName;

/**
 * Validated request for creating a wallet and optionally provisioning its first receiving address.
 * Extended public key and descriptor values are public derivation material; private keys are never accepted here.
 * @param kind requested wallet category
 * @param name optional canonical name, defaulted by the application when absent
 * @param label optional user-facing label, limited to 96 characters
 * @param xpub account extended public key used for address derivation
 * @param descriptor output descriptor used by supported Bitcoin wallet integrations
 * @param fingerprint master key fingerprint associated with the derivation material
 * @param derivationPath account derivation path associated with the extended key
 * @param initialAddress optional precomputed address to register during creation
 * @param initialAddressDerivationPath derivation path that produced the initial address
 * @param initialAddressDerivationIndex child index that produced the initial address
 * @param initialAddressProviderReference identifier returned by the external address provider
 * @param issueInitialAddress whether wallet creation should request an initial address
 */
public record KfeCreateWalletRequest(
        @NotNull KfeWalletKind kind,
        KfeWalletName name,
        @Size(max = 96) String label,
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
