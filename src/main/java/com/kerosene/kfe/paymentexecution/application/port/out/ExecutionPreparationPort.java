package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.result.ExecutionPreparation;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import java.util.Optional;

/** Loads committed provider-dispatch data in a short transaction before rail I/O begins. */
public interface ExecutionPreparationPort {
    /** Returns after the short preparation transaction; empty means no external execution is needed. */
    /** @param claim current durable command lease @return immutable dispatch context, or empty for commands requiring no external call */
    Optional<ExecutionPreparation> prepare(ExecutionClaim claim);
}
