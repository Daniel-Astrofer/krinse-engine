package com.kerosene.kfe.paymentexecution.adapters.out.ledger;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentFeeSettlementPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.ledger.adapters.out.persistence.settlement.KfeFeeSettlementService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Uses the managed payment and preserves the existing fee settlement/idempotency rules. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class LegacyPaymentFeeSettlementAdapter implements PaymentFeeSettlementPort {

    private final KfeTransactionRepository repository;
    private final KfeFeeSettlementService feeSettlementService;

    public LegacyPaymentFeeSettlementAdapter(
            KfeTransactionRepository repository,
            KfeFeeSettlementService feeSettlementService) {
        this.repository = repository;
        this.feeSettlementService = feeSettlementService;
    }

    @Override
    public void settleFee(PaymentExecutionId executionId) {
        Objects.requireNonNull(executionId, "payment execution id is required");
        var transaction = repository.findById(executionId.value())
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
        feeSettlementService.creditKeroseneFee(transaction);
    }
}
