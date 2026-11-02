package com.kerosene.kfe.bootstrap.config.financial;

import com.kerosene.common.financial.operations.FinancialRailProductionSafetyPort;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** KFE owns and enforces production rail configuration before accepting traffic. */
@Component
@Profile({"prod", "production"})
public class KfeFinancialRailProductionGate implements ApplicationRunner {
    private final FinancialRailProductionSafetyPort safety;
    public KfeFinancialRailProductionGate(FinancialRailProductionSafetyPort safety) { this.safety = safety; }
    @Override public void run(ApplicationArguments args) {
        var violations = safety.collectProductionViolations();
        if (!violations.isEmpty()) throw new IllegalStateException("Unsafe financial rail configuration: " + String.join("; ", violations));
    }
}
