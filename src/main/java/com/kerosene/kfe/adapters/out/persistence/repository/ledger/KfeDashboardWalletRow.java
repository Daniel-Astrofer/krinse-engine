package com.kerosene.kfe.adapters.out.persistence.repository.ledger;

import java.time.LocalDateTime;
import java.util.UUID;

/** Read-only projection of wallet and balance fields required by dashboard responses. */
public interface KfeDashboardWalletRow {
    /** @return wallet identifier represented by this dashboard row */
    UUID getWalletId();

    /** @return custody/control kind stored for the wallet */
    String getKind();

    /** @return current wallet operational state */
    String getStatus();

    /** @return user-facing wallet label */
    String getLabel();

    /** @return asset code for the projected balance */
    String getAsset();

    /** @return whether the wallet is enabled for spending, or {@code null} if not projected */
    Boolean getSpendable();

    /** @return available, unreserved satoshi balance for the wallet/asset */
    Long getAvailableSats();

    /** @return satoshi amount pending settlement or confirmation */
    Long getPendingSats();

    /** @return satoshi amount locked for in-flight operations */
    Long getLockedSats();

    /** @return satoshi amount under automatic hold policy */
    Long getAutoHoldSats();

    /** @return amount observed externally but not yet reconciled, in satoshis */
    Long getObservedSats();

    /** @return active receiving address selected for the wallet, if available */
    String getActiveAddress();

    /** @return wallet creation time used in dashboard ordering */
    LocalDateTime getCreatedAt();

    /** @return most recent wallet update time */
    LocalDateTime getUpdatedAt();
}
