package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.result.CancellationEligibilitySnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import java.util.Optional;

/** Independent read projection: must enforce participant visibility and request ownership in SQL. */
public interface PaymentCancellationQueryPort {
    /** Finds display data only when the user is a participant and owns any linked request. */
    /** @param userId authenticated viewer @param executionId requested execution @return scoped eligibility snapshot, or empty when not visible */
    Optional<CancellationEligibilitySnapshot> findParticipantVisible(long userId, PaymentExecutionId executionId);
}
