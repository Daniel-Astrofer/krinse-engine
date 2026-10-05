package com.kerosene.kfe.wallet.adapters.out.bitcoin;

import com.kerosene.kfe.wallet.adapters.out.persistence.KfeDerivationCursorService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.kerosene.common.infra.logging.LogSanitizer;
import com.kerosene.common.service.AddressDerivationService;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;

/** Issues product receive addresses from a public BIP84 xpub or explicitly enabled Bitcoin Core wallet. */
@Service
public class KfeReceiveAddressIssuer {

    /** Public-key derivation implementation for product receive addresses. */
    private final AddressDerivationService addressDerivationService;
    /** Persistent monotonic cursor that prevents address-index reuse. */
    private final KfeDerivationCursorService cursorService;
    /** Optional Core wallet used only when the corresponding feature flag is enabled. */
    private final BitcoinCoreRpcClient bitcoinCoreRpcClient;
    /** Configured platform-level public derivation key; never private key material. */
    private final String platformMasterXpub;
    /** Explicit opt-in for Core-managed product receive address issuance. */
    private final boolean bitcoinCoreWalletAddressEnabled;

    /**
     * Issues product custodial receive addresses from {@code bitcoin.platform.master-xpub}
     * (BIP84 HD) or Bitcoin Core wallet.
     *
     * <p><b>Not</b> the vault-mesh treasury deposit: mesh deposit is Taproot
     * {@code GET /v1/bitcoin/deposit} ({@code tr()} / {@code tb1p…}) from the FROST group
     * verifying key. Do not treat this xpub path as the mesh omnibus address.
     */
    public KfeReceiveAddressIssuer(
            AddressDerivationService addressDerivationService,
            KfeDerivationCursorService cursorService,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            @Value("${bitcoin.platform.master-xpub:${bitcoin.hot-wallet.xpub:}}") String platformMasterXpub,
            @Value("${kfe.receive.bitcoin-core-wallet-address-enabled:false}")
            boolean bitcoinCoreWalletAddressEnabled) {
        this.addressDerivationService = addressDerivationService;
        this.cursorService = cursorService;
        this.bitcoinCoreRpcClient = bitcoinCoreRpcClient.getIfAvailable();
        this.platformMasterXpub = platformMasterXpub != null ? platformMasterXpub.trim() : "";
        this.bitcoinCoreWalletAddressEnabled = bitcoinCoreWalletAddressEnabled;
    }

    /** Reports whether either configured product address-issuance backend is usable. */
    public boolean canIssue() {
        return !platformMasterXpub.isBlank() || (bitcoinCoreWalletAddressEnabled && bitcoinCoreRpcClient != null);
    }

    /** Issues from the platform xpub when configured, otherwise from the opted-in Core wallet. */
    public IssuedAddress issue(String label) {
        if (!platformMasterXpub.isBlank()) {
            int index = cursorService.nextIndex(KfeDerivationCursorService.KFE_BIP84_EXTERNAL);
            AddressDerivationService.DerivedAddress derived =
                    addressDerivationService.deriveAddressDetailsFromXpub(platformMasterXpub, index);
            return new IssuedAddress(
                    derived.address(),
                    "m/84'/0'/0'/0/" + derived.index(),
                    derived.index(),
                    "KFE_PLATFORM_XPUB:" + LogSanitizer.fingerprint(platformMasterXpub));
        }

        if (bitcoinCoreWalletAddressEnabled && bitcoinCoreRpcClient != null) {
            try {
                String address = bitcoinCoreRpcClient.getNewAddress(label != null ? label : "kfe-receive");
                return new IssuedAddress(
                        address,
                        "bitcoin-core-wallet:" + bitcoinCoreRpcClient.walletName(),
                        -1,
                        "KFE_BITCOIN_CORE_WALLET");
            } catch (RuntimeException rpcFailure) {
                throw new IllegalStateException(
                        "O serviço de depósito on-chain está temporariamente indisponível. "
                                + "O nó Bitcoin não respondeu. Tente novamente em alguns minutos.",
                        rpcFailure);
            }
        }

        throw new IllegalStateException(
                "KFE receive address issuance requires bitcoin.platform.master-xpub or Bitcoin Core wallet issuance.");
    }

    /**
     * Issued public address and the metadata needed to audit its derivation source.
     *
     * @param address public Bitcoin destination
     * @param derivationPath path or provider wallet label describing how it was issued
     * @param derivationIndex child index, or -1 when the provider does not expose an index
     * @param providerReference safe provider identity; it contains no private key material
     */
    public record IssuedAddress(
            String address,
            String derivationPath,
            int derivationIndex,
            String providerReference) {
    }
}
