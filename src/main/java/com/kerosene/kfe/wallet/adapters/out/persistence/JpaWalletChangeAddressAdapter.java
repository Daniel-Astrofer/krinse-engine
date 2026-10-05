package com.kerosene.kfe.wallet.adapters.out.persistence;

import org.springframework.stereotype.Component;
import com.kerosene.common.service.AddressDerivationService;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressRole;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.wallet.application.port.out.WalletChangeAddressPort;
import com.kerosene.kfe.wallet.domain.model.WalletSnapshot;

import java.util.List;

/** Reuses or persists a wallet-controlled change address derived from the wallet xpub. */
@Component
public final class JpaWalletChangeAddressAdapter implements WalletChangeAddressPort {
    /** Address persistence boundary used to reuse active change addresses and store new ones. */
    private final KfeWalletAddressRepository addresses;
    /** Public-key derivation service used when no active change address is present. */
    private final AddressDerivationService derivation;

    /** Creates the adapter with address persistence and public derivation services. */
    public JpaWalletChangeAddressAdapter(
            KfeWalletAddressRepository addresses,
            AddressDerivationService derivation) {
        this.addresses = addresses;
        this.derivation = derivation;
    }

    /** Returns an existing active change address or derives and persists one from the wallet xpub. */
    @Override
    public String issueChangeAddress(WalletSnapshot wallet) {
        if (wallet == null || wallet.xpub() == null || wallet.xpub().isBlank()) {
            throw new IllegalStateException("Cold wallet is missing xpub for change derivation.");
        }
        List<KfeWalletAddressEntity> active = addresses.findByWalletIdAndStatusOrderByCreatedAtDesc(
                wallet.id(), KfeWalletAddressStatus.ACTIVE);
        for (KfeWalletAddressEntity row : active) {
            if (row.getAddressRole() == KfeWalletAddressRole.CHANGE
                    && row.getAddress() != null && !row.getAddress().isBlank()) {
                return row.getAddress().trim();
            }
        }
        String address = derivation.deriveAddressFromXpub(wallet.xpub(), 0, true);
        if (address == null || address.isBlank()) {
            throw new IllegalStateException("Failed to derive cold change address.");
        }
        KfeWalletAddressEntity row = new KfeWalletAddressEntity();
        row.setWalletId(wallet.id());
        row.setAddress(address.trim());
        row.setAddressRole(KfeWalletAddressRole.CHANGE);
        row.setStatus(KfeWalletAddressStatus.ACTIVE);
        addresses.save(row);
        return row.getAddress();
    }
}
