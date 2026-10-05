package com.kerosene.kfe.paymentexecution.adapters.out.settlement;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentGateBalancePort;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Keeps the source BTC balance row locked until the owning submit transaction ends. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class LegacyPaymentGateBalanceAdapter implements PaymentGateBalancePort {
    private final KfeBalanceService balances;

    public LegacyPaymentGateBalanceAdapter(KfeBalanceService balances) { this.balances = balances; }

    @Override
    public long lockAvailable(UUID walletId) {
        return balances.requireForUpdate(walletId, "BTC").getAvailableSats();
    }
}
