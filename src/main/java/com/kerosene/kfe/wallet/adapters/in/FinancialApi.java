package com.kerosene.kfe.wallet.adapters.in;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeAddressResponse;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeColdWalletPsbtRequest;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeColdWalletPsbtResponse;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeCreateWalletRequest;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeReceivingCapabilitiesResponse;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeUpdateWalletRequest;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeUtxoResponse;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeWalletNameOption;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeWalletResponse;
import com.kerosene.kfe.wallet.adapters.in.KfeWalletNetworkService;
import com.kerosene.kfe.wallet.adapters.in.KfeWalletService;
import com.kerosene.kfe.wallet.application.command.CreateColdPsbtCommand;
import com.kerosene.kfe.wallet.application.command.ListWalletUtxosCommand;
import com.kerosene.kfe.wallet.application.port.in.CreateColdPsbtUseCase;
import com.kerosene.kfe.wallet.application.port.in.ListWalletUtxosUseCase;
import com.kerosene.kfe.wallet.application.port.in.WalletLifecycleUseCase;
import com.kerosene.kfe.wallet.application.command.CreateWalletCommand;
import com.kerosene.kfe.wallet.application.command.UpdateWalletCommand;
import com.kerosene.kfe.wallet.application.command.WalletCommand;
import com.kerosene.kfe.wallet.domain.model.WalletKind;
import com.kerosene.kfe.wallet.application.result.WalletView;
import com.kerosene.kfe.wallet.application.result.WalletAddressView;
import com.kerosene.kfe.wallet.domain.model.ColdPsbtRequest;
import com.kerosene.kfe.wallet.domain.model.Outpoint;

import java.util.List;
import java.util.UUID;

/** Bridges legacy financial endpoints to wallet use cases and compatibility services. */
@Service
public class FinancialApi {

    /** Legacy wallet lifecycle implementation used when the new use case is not wired. */
    private final KfeWalletService walletService;
    /** Legacy network and cold-workflow operations retained during application-boundary migration. */
    private final KfeWalletNetworkService walletNetworkService;
    /** Optional use case for owner-scoped wallet UTXO projection. */
    private final ListWalletUtxosUseCase listWalletUtxosUseCase;
    /** Optional use case for preparing a cold-wallet PSBT. */
    private final CreateColdPsbtUseCase createColdPsbtUseCase;
    /** Optional use case for wallet lifecycle operations. */
    private final WalletLifecycleUseCase walletLifecycleUseCase;

    /** Creates a legacy-only facade for isolated callers that do not provide migrated use cases. */
    public FinancialApi(
            KfeWalletService walletService,
            KfeWalletNetworkService walletNetworkService) {
        this(walletService, walletNetworkService, null, null, null);
    }

    /** Creates the facade with migrated use cases and legacy fallbacks. */
    @Autowired
    public FinancialApi(
            KfeWalletService walletService,
            KfeWalletNetworkService walletNetworkService,
            ListWalletUtxosUseCase listWalletUtxosUseCase,
            CreateColdPsbtUseCase createColdPsbtUseCase,
            WalletLifecycleUseCase walletLifecycleUseCase) {
        this.walletService = walletService;
        this.walletNetworkService = walletNetworkService;
        this.listWalletUtxosUseCase = listWalletUtxosUseCase;
        this.createColdPsbtUseCase = createColdPsbtUseCase;
        this.walletLifecycleUseCase = walletLifecycleUseCase;
    }

    /** Maps the HTTP request into an application command when available, otherwise delegates to legacy logic. */
    public KfeWalletResponse createWallet(Long userId, KfeCreateWalletRequest request) {
        if (walletLifecycleUseCase != null) {
            return toWalletResponse(walletLifecycleUseCase.create(new CreateWalletCommand(
                    userId,
                    WalletKind.valueOf(request.kind().name()),
                    request.name() == null ? null : request.name().name(),
                    request.label(),
                    request.xpub(),
                    request.descriptor(),
                    request.fingerprint(),
                    request.derivationPath(),
                    request.initialAddress(),
                    request.initialAddressDerivationPath(),
                    request.initialAddressDerivationIndex(),
                    request.initialAddressProviderReference(),
                    request.issueInitialAddress())));
        }
        return walletService.createWallet(userId, request);
    }

    /** Lists only the specified owner's wallets through the active lifecycle implementation. */
    public List<KfeWalletResponse> wallets(Long userId) {
        if (walletLifecycleUseCase != null) {
            return walletLifecycleUseCase.list(userId).stream().map(FinancialApi::toWalletResponse).toList();
        }
        return walletService.listWallets(userId);
    }

    /** Updates the owner-scoped label and maps the application projection to the API response. */
    public KfeWalletResponse updateWallet(Long userId, UUID walletId, KfeUpdateWalletRequest request) {
        if (walletLifecycleUseCase != null) {
            return toWalletResponse(walletLifecycleUseCase.update(
                    new UpdateWalletCommand(userId, walletId, request.label())));
        }
        return walletService.updateWallet(userId, walletId, request);
    }

    /** Archives the owner-scoped wallet through the lifecycle use case or legacy service. */
    public KfeWalletResponse archiveWallet(Long userId, UUID walletId) {
        if (walletLifecycleUseCase != null) {
            return toWalletResponse(walletLifecycleUseCase.archive(new WalletCommand(userId, walletId)));
        }
        return walletService.archiveWallet(userId, walletId);
    }

    /** Returns wallet-name options from the legacy catalog consumed by existing clients. */
    public List<KfeWalletNameOption> walletNames() {
        return walletService.availableWalletNames();
    }

    /** Rotates the owner's receive address and maps the result to its HTTP representation. */
    public KfeAddressResponse rotateAddress(Long userId, UUID walletId) {
        if (walletLifecycleUseCase != null) {
            return toAddressResponse(walletLifecycleUseCase.rotateAddress(new WalletCommand(userId, walletId)));
        }
        return walletService.rotateAddress(userId, walletId);
    }

    /** Maps owner-scoped UTXO snapshots to API DTOs, with a legacy network-service fallback. */
    public List<KfeUtxoResponse> walletUtxos(Long userId, UUID walletId) {
        if (listWalletUtxosUseCase != null) {
            return listWalletUtxosUseCase.list(new ListWalletUtxosCommand(userId, walletId)).utxos().stream()
                    .map(utxo -> new KfeUtxoResponse(
                            utxo.outpoint().txid(),
                            utxo.outpoint().vout(),
                            utxo.valueSats(),
                            utxo.scriptPubKey(),
                            utxo.address(),
                            utxo.confirmations()))
                    .toList();
        }
        return walletNetworkService.listUtxos(userId, walletId);
    }

    /**
     * Converts the public PSBT request to domain values and maps the prepared workflow result.
     * Invalid/blank caller input outpoints are discarded here; the use case validates any remaining
     * requested set against live wallet-owned outputs before building the transaction.
     */
    public KfeColdWalletPsbtResponse createColdWalletPsbt(
            Long userId,
            UUID walletId,
            KfeColdWalletPsbtRequest request) {
        if (createColdPsbtUseCase != null) {
            List<Outpoint> requestedInputs = request.inputs() == null
                    ? List.of()
                    : request.inputs().stream()
                            .filter(input -> input != null && input.txid() != null && !input.txid().isBlank())
                            .map(input -> new Outpoint(input.txid(), input.vout()))
                            .toList();
            var result = createColdPsbtUseCase.create(new CreateColdPsbtCommand(
                    userId,
                    walletId,
                    new ColdPsbtRequest(
                            request.destinationAddress(),
                            request.amountSats(),
                            request.confirmationTarget(),
                            request.feeRateSatsPerVbyte(),
                            requestedInputs),
                    request.totpCode()));
            List<KfeColdWalletPsbtRequest.Input> responseInputs = result.inputs().stream()
                    .map(input -> new KfeColdWalletPsbtRequest.Input(input.txid(), input.vout()))
                    .toList();
            return new KfeColdWalletPsbtResponse(
                    result.workflowId(),
                    result.psbt(),
                    result.psbtHash(),
                    result.feeSats(),
                    result.amountSats(),
                    result.destinationAddress(),
                    responseInputs);
        }
        return walletNetworkService.createColdWalletPsbt(userId, walletId, request);
    }

    /** Resolves receiver capabilities without requesting sender-specific source-wallet options. */
    public KfeReceivingCapabilitiesResponse receivingCapabilities(String receiverIdentifier) {
        return walletNetworkService.receivingCapabilities(receiverIdentifier);
    }

    /** Resolves destination capabilities and, when supplied, sender-compatible source wallets. */
    public KfeReceivingCapabilitiesResponse receivingCapabilities(
            Long senderUserId,
            String receiverIdentifier) {
        return walletNetworkService.receivingCapabilities(senderUserId, receiverIdentifier);
    }

    /** Converts application wallet state to persistence-enum-compatible API fields. */
    private static KfeWalletResponse toWalletResponse(WalletView view) {
        return new KfeWalletResponse(
                view.id(),
                com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind.valueOf(view.kind().name()),
                com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus.valueOf(view.status().name()),
                view.label(),
                view.walletName(),
                view.walletTypeDescription(),
                view.asset(),
                view.spendable(),
                view.xpubConfigured(),
                view.mpcKeyConfigured(),
                view.activeAddress(),
                view.createdAt(),
                view.updatedAt());
    }

    /** Converts nullable application address role/status into the legacy API enum representation. */
    private static KfeAddressResponse toAddressResponse(WalletAddressView view) {
        return new KfeAddressResponse(
                view.id(),
                view.walletId(),
                view.address(),
                view.role() == null
                        ? null
                        : com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressRole.valueOf(view.role().name()),
                view.status() == null
                        ? null
                        : com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressStatus.valueOf(view.status()),
                view.derivationPath(),
                view.derivationIndex(),
                view.providerReference(),
                view.createdAt(),
                view.retiredAt());
    }
}
