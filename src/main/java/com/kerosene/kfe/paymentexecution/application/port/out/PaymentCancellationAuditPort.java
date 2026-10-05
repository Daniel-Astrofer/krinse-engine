package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentCancellationSnapshot;

/** Appends a cancellation event, including the inbound destination wallet, in the financial commit. */
public interface PaymentCancellationAuditPort {
    /** Appends state and participant-wallet facts from the fenced pre-cancellation snapshot. */
    /** @param previous authoritative state read after the cancellation fence */
    void recordCancelled(PaymentCancellationSnapshot previous);
}
