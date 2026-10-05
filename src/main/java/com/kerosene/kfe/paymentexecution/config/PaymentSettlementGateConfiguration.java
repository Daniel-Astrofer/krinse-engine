package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentSettlementGateService;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementGatePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configures settlement safety policy and assembles its balance, solvency, quorum, and telemetry ports. */
@Configuration
public class PaymentSettlementGateConfiguration {
    /** Binds risk, proof-of-reserves, and Vault Mesh constitution settings into one immutable policy. */
    @Bean
    SettlementGatePolicy settlementGatePolicy(
            @Value("${kfe.settlement.lightning.risk-gate-mode:enforce}") String lightningRiskGateMode,
            @Value("${kfe.settlement.por-gate-enabled:true}") boolean porGateEnabled,
            @Value("${kfe.vaultmesh.constitution.member-count:3}") int constitutionMemberCount,
            @Value("${kfe.vaultmesh.constitution.threshold:2}") int constitutionThreshold) {
        return new SettlementGatePolicy(lightningRiskGateMode, porGateEnabled, false,
                constitutionMemberCount, constitutionThreshold);
    }

    /** Registers the settlement gate that evaluates environment evidence before payment routing. */
    @Bean
    PaymentSettlementGateService paymentSettlementGateService(PaymentGateBalancePort balances,
            PaymentGateSolvencyPort solvency, PaymentGateQuorumPort quorum, PaymentGateLightningPort lightning,
            PaymentGateEnvironmentPort environment, PaymentGateAuditPort audit, PaymentGateTelemetryPort telemetry,
            SettlementGatePolicy policy) {
        return new PaymentSettlementGateService(balances, solvency, quorum, lightning, environment, audit, telemetry, policy);
    }
}
