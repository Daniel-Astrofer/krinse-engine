package com.kerosene.kfe.wallet.adapters.out.persistence;

import org.springframework.stereotype.Component;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeAddressResponse;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeCreateWalletRequest;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeUpdateWalletRequest;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeWalletResponse;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletName;
import com.kerosene.kfe.wallet.adapters.in.KfeWalletService;
import com.kerosene.kfe.wallet.application.command.CreateWalletCommand;
import com.kerosene.kfe.wallet.application.command.UpdateWalletCommand;
import com.kerosene.kfe.wallet.application.command.WalletCommand;
import com.kerosene.kfe.wallet.application.port.out.WalletLifecyclePort;
import com.kerosene.kfe.wallet.application.result.WalletAddressView;
import com.kerosene.kfe.wallet.application.result.WalletView;
import com.kerosene.kfe.wallet.domain.model.AddressRole;
import com.kerosene.kfe.wallet.domain.model.WalletKind;
import com.kerosene.kfe.wallet.domain.model.WalletStatus;

import java.util.List;

/**
 * Compatibility adapter for the existing persistence/provisioning implementation.
 * The HTTP/application boundary no longer depends on KfeWalletService directly.
 */
@Component
public final class LegacyWalletLifecycleAdapter implements WalletLifecyclePort {
    /** Existing lifecycle implementation retained behind the new application port. */
    private final KfeWalletService wallets;

    /** Creates the compatibility adapter around the established wallet service. */
    public LegacyWalletLifecycleAdapter(KfeWalletService wallets) {
        this.wallets = wallets;
    }

    /** Maps the application command to the legacy request and converts its response projection. */
    @Override
    public WalletView create(CreateWalletCommand command) {
        KfeCreateWalletRequest request = new KfeCreateWalletRequest(
                KfeWalletKind.valueOf(command.kind().name()),
                command.name() == null ? null : KfeWalletName.valueOf(command.name()),
                command.label(),
                command.xpub(),
                command.descriptor(),
                command.fingerprint(),
                command.derivationPath(),
                command.initialAddress(),
                command.initialAddressDerivationPath(),
                command.initialAddressDerivationIndex(),
                command.initialAddressProviderReference(),
                command.issueInitialAddress());
        return map(wallets.createWallet(command.ownerId(), request));
    }

    /** Lists owner-scoped legacy responses and maps them into stable application views. */
    @Override
    public List<WalletView> list(long ownerId) {
        return wallets.listWallets(ownerId).stream().map(LegacyWalletLifecycleAdapter::map).toList();
    }

    /** Maps an owner-scoped label update through the existing wallet service. */
    @Override
    public WalletView update(UpdateWalletCommand command) {
        return map(wallets.updateWallet(
                command.ownerId(), command.walletId(), new KfeUpdateWalletRequest(command.label())));
    }

    /** Delegates owner-scoped archival and maps the returned wallet state. */
    @Override
    public WalletView archive(WalletCommand command) {
        return map(wallets.archiveWallet(command.ownerId(), command.walletId()));
    }

    /** Delegates receive-address rotation and maps its address lifecycle projection. */
    @Override
    public WalletAddressView rotateAddress(WalletCommand command) {
        return map(wallets.rotateAddress(command.ownerId(), command.walletId()));
    }

    /** Delegates ensure-active-address behavior and returns the resulting address string. */
    @Override
    public String ensureActiveReceiveAddress(WalletCommand command) {
        return wallets.ensureActiveReceiveAddress(command.ownerId(), command.walletId());
    }

    /** Converts API wallet enums and metadata into the application-owned wallet view. */
    private static WalletView map(KfeWalletResponse response) {
        return new WalletView(
                response.id(),
                WalletKind.valueOf(response.kind().name()),
                WalletStatus.valueOf(response.status().name()),
                response.label(),
                response.walletName(),
                response.walletTypeDescription(),
                response.asset(),
                response.spendable(),
                response.xpubConfigured(),
                response.mpcKeyConfigured(),
                response.activeAddress(),
                response.createdAt(),
                response.updatedAt());
    }

    /** Converts API address fields, defaulting a legacy missing role to RECEIVE. */
    private static WalletAddressView map(KfeAddressResponse response) {
        return new WalletAddressView(
                response.id(),
                response.walletId(),
                response.address(),
                response.role() == null ? AddressRole.RECEIVE : AddressRole.valueOf(response.role().name()),
                response.status() == null ? null : response.status().name(),
                response.derivationPath(),
                response.derivationIndex(),
                response.providerReference(),
                response.createdAt(),
                response.retiredAt());
    }
}
