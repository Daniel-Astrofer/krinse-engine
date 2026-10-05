package com.kerosene.kfe.paymentexecution.adapters.out.remote;

import com.kerosene.common.financial.operations.FinancialUserDirectoryPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRecipientDirectoryPort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentRecipient;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class FinancialPaymentRecipientDirectoryAdapter implements PaymentRecipientDirectoryPort {
    private final FinancialUserDirectoryPort directory;
    public FinancialPaymentRecipientDirectoryAdapter(FinancialUserDirectoryPort directory) { this.directory = directory; }
    @Override
    public Optional<PaymentRecipient> findByUsername(String username) {
        return directory.findByUsername(username).map(user -> new PaymentRecipient(user.id(), user.active()));
    }
}
