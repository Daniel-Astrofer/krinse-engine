package com.kerosene.kfe.wallet.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.kerosene.kfe.wallet.application.port.in.CreateColdPsbtUseCase;
import com.kerosene.kfe.wallet.application.port.in.ListWalletUtxosUseCase;
import com.kerosene.kfe.wallet.application.port.in.WalletLifecycleUseCase;
import com.kerosene.kfe.wallet.application.port.out.WalletApprovalPort;
import com.kerosene.kfe.wallet.application.port.out.WalletAuditPort;
import com.kerosene.kfe.wallet.application.port.out.WalletChangeAddressPort;
import com.kerosene.kfe.wallet.application.port.out.WalletChainPort;
import com.kerosene.kfe.wallet.application.port.out.WalletDestinationPort;
import com.kerosene.kfe.wallet.application.port.out.WalletHashPort;
import com.kerosene.kfe.wallet.application.port.out.WalletPsbtPort;
import com.kerosene.kfe.wallet.application.port.out.WalletQueryPort;
import com.kerosene.kfe.wallet.application.port.out.WalletWorkflowPort;
import com.kerosene.kfe.wallet.application.usecase.CreateColdPsbtService;
import com.kerosene.kfe.wallet.application.usecase.ListWalletUtxosService;
import com.kerosene.kfe.wallet.application.usecase.WalletLifecycleService;
import com.kerosene.kfe.wallet.application.port.out.WalletLifecyclePort;

/** Wires wallet application use cases from their ports and configured descriptor scan range. */
@Configuration
public class WalletConfiguration {
    /** Registers the lifecycle use case backed by the configured outbound lifecycle port. */
    @Bean
    WalletLifecycleUseCase walletLifecycleUseCase(WalletLifecyclePort lifecycle) {
        return new WalletLifecycleService(lifecycle);
    }

    /** Registers read-only UTXO discovery with a bounded descriptor lookahead. */
    @Bean
    ListWalletUtxosUseCase listWalletUtxosUseCase(
            WalletQueryPort wallets,
            WalletChainPort chain,
            @Value("${kfe.descriptor-scan-range:200}") int descriptorScanRange) {
        return new ListWalletUtxosService(wallets, chain, descriptorScanRange);
    }

    /** Registers cold PSBT orchestration with all authorization, chain, persistence, and audit ports. */
    @Bean
    CreateColdPsbtUseCase createColdPsbtUseCase(
            WalletQueryPort wallets,
            WalletChainPort chain,
            WalletApprovalPort approval,
            WalletDestinationPort destination,
            WalletChangeAddressPort changeAddress,
            WalletPsbtPort psbt,
            WalletWorkflowPort workflows,
            WalletHashPort hash,
            WalletAuditPort audit,
            @Value("${kfe.descriptor-scan-range:200}") int descriptorScanRange) {
        return new CreateColdPsbtService(
                wallets,
                chain,
                approval,
                destination,
                changeAddress,
                psbt,
                workflows,
                hash,
                audit,
                descriptorScanRange);
    }
}
