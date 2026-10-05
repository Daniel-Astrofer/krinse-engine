package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentWalletSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Looks up wallet snapshots with explicit ownership and freshness semantics. */
public interface PaymentWalletLookupPort {
    /** Requires the financial transaction; scope in SQL and refresh after waiting for the lock. */
    Optional<PaymentWalletSnapshot> lockOwnedSource(long userId, UUID walletId);
    /** Finds a wallet by identifier without asserting that it belongs to a caller. */
    Optional<PaymentWalletSnapshot> findById(UUID walletId);
    /** Finds a destination wallet only when it is owned by the supplied user. */
    Optional<PaymentWalletSnapshot> findOwnedDestination(long userId, UUID walletId);
    /** Finds a wallet associated with the supplied address, if one is known. */
    Optional<PaymentWalletSnapshot> findByAddress(String address);
    /** Lists the user's wallets in newest-first order. */
    List<PaymentWalletSnapshot> findForUserNewestFirst(long userId);
}
