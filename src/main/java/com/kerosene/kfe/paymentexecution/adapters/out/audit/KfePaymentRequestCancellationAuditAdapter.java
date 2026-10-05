package com.kerosene.kfe.paymentexecution.adapters.out.audit;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationAuditPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationSnapshot;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** Preserves the existing event without serializing the invoice or provider cancellation credentials. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class KfePaymentRequestCancellationAuditAdapter implements PaymentRequestCancellationAuditPort {

    /** Append-only audit writer for linked payment-request cancellation. */
    private final KfeAuditLogService auditLogService;

    /** Creates the adapter with the shared audit log service. */
    public KfePaymentRequestCancellationAuditAdapter(KfeAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** Records request ID, public ID, prior status, and rail without invoice/provider credentials. */
    @Override
    public void recordCancelled(PaymentRequestCancellationSnapshot previous) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentRequestId", previous.id().toString());
        payload.put("publicId", previous.publicId());
        payload.put("previousStatus", previous.status().name());
        payload.put("rail", previous.rail().name());
        auditLogService.record(
                "KFE_PAYMENT_REQUEST_CANCELLED", null, previous.walletId(), null, null, payload);
    }
}
