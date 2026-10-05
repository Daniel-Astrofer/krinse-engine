package com.kerosene.kfe.adapters.out.rail.onchain;

import com.kerosene.kfe.adapters.out.rail.signing.KfeQuorumPsbtSigningService;
import org.springframework.stereotype.Component;

/**
 * Adapts quorum-signed Bitcoin Core execution to the on-chain payment gateway contract.
 * Provider identity reflects that funds are prepared and authorized through the configured signer quorum.
 */
@Component("bitcoinCorePsbtKfeOnchainPaymentGateway")
public class KfeOnchainCustodyAdapter implements KfeOnchainPaymentGateway {

    /** Service that validates funding feasibility and executes quorum-protected PSBT workflows. */
    private final KfeQuorumPsbtSigningService quorumPsbtSigningService;

    /**
     * Creates the gateway adapter around the quorum PSBT service.
     *
     * @param quorumPsbtSigningService funding preflight and signed transaction execution service
     */
    public KfeOnchainCustodyAdapter(KfeQuorumPsbtSigningService quorumPsbtSigningService) {
        this.quorumPsbtSigningService = quorumPsbtSigningService;
    }

    /** @return stable identifier for Bitcoin Core execution protected by signer quorum */
    @Override
    public String providerName() {
        return "BITCOIN_CORE_QUORUM";
    }

    /**
     * Delegates funding preflight and maps fee, PSBT digest, signer count, and provider identity.
     *
     * @param command requested destination, amount, fee ceiling, and idempotency context
     * @return available preflight summary for the configured quorum service
     */
    @Override
    public OnchainFundingPreflight preflightOnchain(OnchainPreflightCommand command) {
        KfeQuorumPsbtSigningService.OnchainFundingPreflight preflight = quorumPsbtSigningService.preflight(command);
        return new OnchainFundingPreflight(
                true,
                preflight.feeSats(),
                preflight.psbtHash(),
                preflight.configuredSignerCount(),
                providerName());
    }

    /**
     * Executes the quorum-protected on-chain payment and maps the broadcast transaction result.
     *
     * @param command payment request including authorization and fee policy
     * @return transaction ID, fee, mempool status, and execution metadata
     */
    @Override
    public PaymentResult sendOnchain(OnchainPaymentCommand command) {
        KfeQuorumPsbtSigningService.OnchainExecution result = quorumPsbtSigningService.execute(command);
        return new PaymentResult(
                result.txid(),
                result.txid(),
                null,
                "MEMPOOL",
                result.feeSats(),
                result.metadataJson());
    }
}
