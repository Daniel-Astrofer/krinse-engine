package com.kerosene.kfe.wallet.application.usecase;

import com.kerosene.kfe.wallet.application.command.CreateColdPsbtCommand;
import com.kerosene.kfe.wallet.application.port.in.CreateColdPsbtUseCase;
import com.kerosene.kfe.wallet.application.port.out.WalletApprovalPort;
import com.kerosene.kfe.wallet.application.port.out.WalletAuditPort;
import com.kerosene.kfe.wallet.application.port.out.WalletChangeAddressPort;
import com.kerosene.kfe.wallet.application.port.out.WalletChainPort;
import com.kerosene.kfe.wallet.application.port.out.WalletDestinationPort;
import com.kerosene.kfe.wallet.application.port.out.WalletHashPort;
import com.kerosene.kfe.wallet.application.port.out.WalletPsbtPort;
import com.kerosene.kfe.wallet.application.port.out.WalletQueryPort;
import com.kerosene.kfe.wallet.application.port.out.WalletWorkflowPort;
import com.kerosene.kfe.wallet.application.result.ColdPsbtResult;
import com.kerosene.kfe.wallet.domain.service.WalletPolicy;

import java.util.Map;

/**
 * Orchestrates cold-wallet PSBT preparation while keeping all external effects behind ports.
 *
 * <p>The use case validates wallet ownership and custody policy before approval, selects only
 * wallet-owned live inputs, creates and hashes an unsigned PSBT, persists a workflow bound to that
 * hash and exact input set, and emits an audit event without recording the approval factor.
 */
public final class CreateColdPsbtService implements CreateColdPsbtUseCase {
    /** Owner-scoped lookup for the target wallet and its addresses. */
    private final WalletQueryPort wallets;
    /** Live chain observation source used for safe input selection. */
    private final WalletChainPort chain;
    /** Authorization boundary for the account's configured cold-spend factor. */
    private final WalletApprovalPort approval;
    /** Network-specific validator for the external destination. */
    private final WalletDestinationPort destination;
    /** Issues a wallet-controlled destination for transaction change. */
    private final WalletChangeAddressPort changeAddress;
    /** Builds an unsigned transaction PSBT from the selected outpoints. */
    private final WalletPsbtPort psbt;
    /** Persists the PSBT hash and exact payment/input context as a signing workflow. */
    private final WalletWorkflowPort workflows;
    /** Computes the integrity digest stored with the workflow. */
    private final WalletHashPort hash;
    /** Records the creation event using non-secret metadata. */
    private final WalletAuditPort audit;
    /** Positive descriptor lookahead used while discovering wallet outputs. */
    private final int descriptorScanRange;

    /**
     * Wires the ports required to prepare and persist a cold-signing workflow.
     *
     * @param wallets owner-scoped wallet lookup
     * @param chain live UTXO discovery
     * @param approval cold-spend authorization
     * @param destination Bitcoin address validation
     * @param changeAddress wallet-controlled change address allocation
     * @param psbt unsigned transaction construction
     * @param workflows durable signing workflow persistence
     * @param hash PSBT integrity hashing
     * @param audit security event recording
     * @param descriptorScanRange configured lookahead, clamped to a positive value
     */
    public CreateColdPsbtService(
            WalletQueryPort wallets,
            WalletChainPort chain,
            WalletApprovalPort approval,
            WalletDestinationPort destination,
            WalletChangeAddressPort changeAddress,
            WalletPsbtPort psbt,
            WalletWorkflowPort workflows,
            WalletHashPort hash,
            WalletAuditPort audit,
            int descriptorScanRange) {
        this.wallets = wallets;
        this.chain = chain;
        this.approval = approval;
        this.destination = destination;
        this.changeAddress = changeAddress;
        this.psbt = psbt;
        this.workflows = workflows;
        this.hash = hash;
        this.audit = audit;
        this.descriptorScanRange = Math.max(1, descriptorScanRange);
    }

    /**
     * Prepares an unsigned cold-wallet payment and records its immutable signing context.
     *
     * @param command authenticated owner, wallet, payment request, and approval factor
     * @return created workflow and PSBT details required by the offline signer
     * @throws IllegalArgumentException if the wallet is absent or violates ownership/custody policy
     */
    @Override
    public ColdPsbtResult create(CreateColdPsbtCommand command) {
        var wallet = wallets.findOwned(command.ownerId(), command.walletId())
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        WalletPolicy.requireOwnedActive(wallet, command.ownerId(), command.walletId());
        WalletPolicy.requireBitcoinWallet(wallet);
        WalletPolicy.requireColdPsbtWallet(wallet);
        String destinationAddress = WalletPolicy.requireDestination(command.request());
        destination.requireValidBitcoinAddress(destinationAddress);
        approval.approveColdPsbt(command.ownerId(), command.approvalFactor());

        var live = chain.listUnspent(
                wallet,
                wallets.activeAddresses(wallet.id()),
                descriptorScanRange);
        var selected = WalletPolicy.selectOwnedInputs(live, command.request().requestedInputs());
        String change = changeAddress.issueChangeAddress(wallet);
        var funded = psbt.create(
                selected.stream().map(utxo -> utxo.outpoint()).toList(),
                destinationAddress,
                command.request().amountSats(),
                command.request().confirmationTarget(),
                command.request().feeRateSatsPerVbyte(),
                change);
        String psbtHash = hash.sha256(funded.psbt());
        var inputOutpoints = selected.stream().map(utxo -> utxo.outpoint()).toList();
        var workflowId = workflows.create(
                command.ownerId(),
                wallet.id(),
                funded.psbt(),
                psbtHash,
                funded.feeSats(),
                command.request().amountSats(),
                destinationAddress,
                inputOutpoints);
        audit.record(
                "KFE_COLD_WALLET_PSBT_CREATED",
                wallet.id(),
                Map.of(
                        "walletId", wallet.id().toString(),
                        "psbtHash", psbtHash,
                        "amountSats", Long.toString(command.request().amountSats()),
                        "feeSats", Long.toString(funded.feeSats()),
                        "inputCount", Integer.toString(inputOutpoints.size())));
        return new ColdPsbtResult(
                workflowId,
                funded.psbt(),
                psbtHash,
                funded.feeSats(),
                command.request().amountSats(),
                destinationAddress,
                inputOutpoints);
    }
}
