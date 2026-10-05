package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.result.PaymentRecipient;
import java.util.Optional;

/** Minimal user-directory lookup for internal payment destination resolution. */
public interface PaymentRecipientDirectoryPort {
    /** Finds a recipient by normalized username without exposing user-directory transport types. */
    /** @param username normalized account name @return active-state projection, or empty when absent */
    Optional<PaymentRecipient> findByUsername(String username);
}
