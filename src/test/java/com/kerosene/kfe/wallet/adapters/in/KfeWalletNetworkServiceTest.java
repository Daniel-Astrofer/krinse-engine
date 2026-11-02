package com.kerosene.kfe.wallet.adapters.in;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.wallet.adapters.out.bitcoin.BitcoinAddressValidator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.kerosene.common.financial.approval.DeviceProof;
import com.kerosene.common.financial.approval.FinancialTransactionApprovalPort;
import com.kerosene.common.financial.operations.FinancialUserDirectoryPort;
import com.kerosene.common.service.AddressDerivationService;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeColdWalletPsbtRequest;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeColdWalletPsbtResponse;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeReceivingCapabilitiesResponse;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeUtxoResponse;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfePsbtWorkflowEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;
import com.kerosene.kfe.adapters.out.rail.onchain.BlockchainClient;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningInvoiceGateway;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeWalletNetworkServiceTest {

    private final FinancialUserDirectoryPort userDirectory = mock(FinancialUserDirectoryPort.class);
    private final KfeWalletRepository walletRepository = mock(KfeWalletRepository.class);
    private final KfeWalletAddressRepository addressRepository = mock(KfeWalletAddressRepository.class);
    private final ObjectProvider<BlockchainClient> blockchainClientProvider = mock(ObjectProvider.class);
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider = mock(ObjectProvider.class);
    private final KfeHashService hashService = new KfeHashService();
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);
    private final KfePsbtWorkflowService psbtWorkflowService = mock(KfePsbtWorkflowService.class);
    private final FinancialTransactionApprovalPort transactionApprovalPort = mock(FinancialTransactionApprovalPort.class);
    private final AddressDerivationService addressDerivationService = mock(AddressDerivationService.class);
    private final BitcoinAddressValidator bitcoinAddressValidator = mock(BitcoinAddressValidator.class);
    private final LightningInvoiceGateway lightningInvoiceGateway = mock(LightningInvoiceGateway.class);
    private final KfeWalletNetworkService service = new KfeWalletNetworkService(
            userDirectory,
            walletRepository,
            addressRepository,
            blockchainClientProvider,
            bitcoinCoreProvider,
            hashService,
            auditLogService,
            psbtWorkflowService,
            transactionApprovalPort,
            addressDerivationService,
            bitcoinAddressValidator,
            lightningInvoiceGateway,
            200);

    @Test
    void returnsKfeReceivingCapabilitiesFromActiveWallets() {
        FinancialUserDirectoryPort.FinancialUserHandle user =
                new FinancialUserDirectoryPort.FinancialUserHandle(42L, "alice", true);

        KfeWalletEntity wallet = wallet(KfeWalletKind.INTERNAL);
        KfeWalletAddressEntity address = address(wallet.getId());

        when(userDirectory.findByUsername("alice")).thenReturn(Optional.of(user));
        when(walletRepository.findByUserIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(wallet));
        when(addressRepository.findTopByWalletIdAndStatusOrderByCreatedAtDesc(
                wallet.getId(),
                KfeWalletAddressStatus.ACTIVE)).thenReturn(Optional.of(address));
        when(lightningInvoiceGateway.isLive()).thenReturn(false);

        KfeReceivingCapabilitiesResponse response = service.receivingCapabilities("@alice");

        assertThat(response.canReceiveInternal()).isTrue();
        assertThat(response.canReceiveOnchain()).isTrue();
        assertThat(response.canReceiveLightning()).isFalse();
        assertThat(response.receiverDisplayName()).isEqualTo("@alice");
        assertThat(response.internalWalletId()).isEqualTo(wallet.getId());
        assertThat(response.preferredRail()).isEqualTo("INTERNAL");
        assertThat(response.onchainReceiveAddress()).isEqualTo(address.getAddress());
        assertThat(response.onchainWalletId()).isEqualTo(wallet.getId());
        assertThat(response.availableRails()).containsExactly("INTERNAL", "ONCHAIN");
        assertThat(response.missingRequirements()).containsExactly("KFE_LIGHTNING_RECEIVE_NOT_CONFIGURED");
        assertThat(response.eligibleSourceWallets()).isEmpty();
    }

    @Test
    void receivingCapabilitiesReturnsEligibleSenderWalletsForAuthenticatedSender() {
        FinancialUserDirectoryPort.FinancialUserHandle receiver =
                new FinancialUserDirectoryPort.FinancialUserHandle(42L, "alice", true);
        KfeWalletEntity receiverWallet = wallet(KfeWalletKind.INTERNAL);
        KfeWalletAddressEntity address = address(receiverWallet.getId());

        KfeWalletEntity senderInternal = wallet(KfeWalletKind.INTERNAL);
        senderInternal.setLabel("Hot");
        KfeWalletEntity senderCold = wallet(KfeWalletKind.WATCH_ONLY);
        senderCold.setLabel("Cold");
        senderCold.setSpendable(false);

        when(userDirectory.findByUsername("alice")).thenReturn(Optional.of(receiver));
        when(walletRepository.findByUserIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(receiverWallet));
        when(walletRepository.findByUserIdOrderByCreatedAtDesc(99L))
                .thenReturn(List.of(senderInternal, senderCold));
        when(addressRepository.findTopByWalletIdAndStatusOrderByCreatedAtDesc(
                receiverWallet.getId(),
                KfeWalletAddressStatus.ACTIVE)).thenReturn(Optional.of(address));
        when(lightningInvoiceGateway.isLive()).thenReturn(false);

        KfeReceivingCapabilitiesResponse response =
                service.receivingCapabilities(99L, "@alice");

        assertThat(response.eligibleSourceWallets()).hasSize(2);
        assertThat(response.eligibleSourceWallets().get(0).walletId()).isEqualTo(senderInternal.getId());
        assertThat(response.eligibleSourceWallets().get(0).compatibleRails())
                .containsExactly("INTERNAL", "ONCHAIN");
        assertThat(response.eligibleSourceWallets().get(1).walletId()).isEqualTo(senderCold.getId());
        assertThat(response.eligibleSourceWallets().get(1).compatibleRails())
                .containsExactly("ONCHAIN");
    }

    @Test
    void receivingCapabilitiesEnablesLightningWhenInvoiceGatewayLive() {
        FinancialUserDirectoryPort.FinancialUserHandle user =
                new FinancialUserDirectoryPort.FinancialUserHandle(42L, "alice", true);
        KfeWalletEntity wallet = wallet(KfeWalletKind.INTERNAL);
        KfeWalletAddressEntity address = address(wallet.getId());

        when(userDirectory.findByUsername("alice")).thenReturn(Optional.of(user));
        when(walletRepository.findByUserIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(wallet));
        when(addressRepository.findTopByWalletIdAndStatusOrderByCreatedAtDesc(
                wallet.getId(),
                KfeWalletAddressStatus.ACTIVE)).thenReturn(Optional.of(address));
        when(lightningInvoiceGateway.isLive()).thenReturn(true);

        KfeReceivingCapabilitiesResponse response = service.receivingCapabilities("@alice");

        assertThat(response.canReceiveLightning()).isTrue();
        assertThat(response.availableRails()).contains("LIGHTNING");
        assertThat(response.missingRequirements()).doesNotContain("KFE_LIGHTNING_RECEIVE_NOT_CONFIGURED");
    }

    @Test
    void receivingCapabilitiesResolvesWalletUuidWithoutUsernameDirectoryLookup() {
        FinancialUserDirectoryPort.FinancialUserHandle user =
                new FinancialUserDirectoryPort.FinancialUserHandle(42L, "alice", true);

        KfeWalletEntity wallet = wallet(KfeWalletKind.INTERNAL);
        KfeWalletAddressEntity address = address(wallet.getId());

        when(walletRepository.findById(wallet.getId())).thenReturn(Optional.of(wallet));
        when(userDirectory.findById(42L)).thenReturn(Optional.of(user));
        when(walletRepository.findByUserIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(wallet));
        when(addressRepository.findTopByWalletIdAndStatusOrderByCreatedAtDesc(
                wallet.getId(),
                KfeWalletAddressStatus.ACTIVE)).thenReturn(Optional.of(address));

        KfeReceivingCapabilitiesResponse response =
                service.receivingCapabilities(wallet.getId().toString());

        assertThat(response.canReceiveInternal()).isTrue();
        assertThat(response.receiverDisplayName()).isEqualTo("@alice");
        assertThat(response.internalWalletId()).isEqualTo(wallet.getId());
        assertThat(response.onchainReceiveAddress()).isEqualTo(address.getAddress());
        assertThat(response.preferredRail()).isEqualTo("INTERNAL");
        assertThat(response.missingRequirements()).containsExactly("KFE_LIGHTNING_RECEIVE_NOT_CONFIGURED");
    }

    @Test
    void receivingCapabilitiesPrefersCustodialOnchainAddressWhenPresent() {
        FinancialUserDirectoryPort.FinancialUserHandle user =
                new FinancialUserDirectoryPort.FinancialUserHandle(42L, "alice", true);

        KfeWalletEntity internal = wallet(KfeWalletKind.INTERNAL);
        KfeWalletEntity custodial = wallet(KfeWalletKind.CUSTODIAL_ONCHAIN);
        KfeWalletAddressEntity custodialAddress = address(custodial.getId());
        custodialAddress.setAddress("tb1qcustodialxxxxxxxxxxxxxxxxxxxxxxxxxxxx");

        when(userDirectory.findByUsername("alice")).thenReturn(Optional.of(user));
        when(walletRepository.findByUserIdOrderByCreatedAtDesc(42L))
                .thenReturn(List.of(internal, custodial));
        when(addressRepository.findTopByWalletIdAndStatusOrderByCreatedAtDesc(
                internal.getId(),
                KfeWalletAddressStatus.ACTIVE)).thenReturn(Optional.empty());
        when(addressRepository.findTopByWalletIdAndStatusOrderByCreatedAtDesc(
                custodial.getId(),
                KfeWalletAddressStatus.ACTIVE)).thenReturn(Optional.of(custodialAddress));

        KfeReceivingCapabilitiesResponse response = service.receivingCapabilities("@alice");

        assertThat(response.canReceiveInternal()).isTrue();
        assertThat(response.canReceiveOnchain()).isTrue();
        assertThat(response.onchainReceiveAddress()).isEqualTo(custodialAddress.getAddress());
        assertThat(response.onchainWalletId()).isEqualTo(custodial.getId());
        assertThat(response.preferredRail()).isEqualTo("INTERNAL");
        assertThat(response.availableRails()).containsExactly("INTERNAL", "ONCHAIN");
    }

    @Test
    void receivingCapabilitiesReturnsNotReadyForUnknownWalletUuid() {
        UUID missingWalletId = UUID.randomUUID();
        when(walletRepository.findById(missingWalletId)).thenReturn(Optional.empty());

        KfeReceivingCapabilitiesResponse response =
                service.receivingCapabilities(missingWalletId.toString());

        assertThat(response.canReceiveInternal()).isFalse();
        assertThat(response.missingRequirements()).containsExactly("RECEIVER_NOT_READY");
        assertThat(response.internalWalletId()).isNull();
    }

    @Test
    void listsUtxosForActiveKfeWalletAddresses() {
        KfeWalletEntity wallet = wallet(KfeWalletKind.WATCH_ONLY);
        KfeWalletAddressEntity address = address(wallet.getId());
        BlockchainClient blockchainClient = mock(BlockchainClient.class);

        when(walletRepository.findByIdAndUserId(wallet.getId(), 42L)).thenReturn(Optional.of(wallet));
        when(blockchainClientProvider.getIfAvailable()).thenReturn(blockchainClient);
        when(addressRepository.findByWalletIdAndStatusOrderByCreatedAtDesc(
                wallet.getId(),
                KfeWalletAddressStatus.ACTIVE)).thenReturn(List.of(address));
        when(blockchainClient.getUnspentOutputsMerged(address.getAddress())).thenReturn(List.of(
                new BlockchainClient.AddressUtxo("txid-1", 0, 1000L, "0014abcd", 3, address.getAddress())));

        List<KfeUtxoResponse> response = service.listUtxos(42L, wallet.getId());

        assertThat(response).containsExactly(new KfeUtxoResponse(
                "txid-1",
                0,
                1000L,
                "0014abcd",
                address.getAddress(),
                3));
    }

    @Test
    void createsColdWalletPsbtOnlyForWatchOnlyWallets() {
        KfeWalletEntity wallet = wallet(KfeWalletKind.WATCH_ONLY);
        BitcoinCoreRpcClient bitcoinCore = mock(BitcoinCoreRpcClient.class);
        KfeColdWalletPsbtRequest request = new KfeColdWalletPsbtRequest(
                "bc1qdestxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                10_000L,
                6,
                null,
                List.of(new KfeColdWalletPsbtRequest.Input("txid-1", 0)),
                "totp-code");

        wallet.setXpub("tpubDTestXpubMaterialForUnitTestsOnlyXXXXXXXXXXXXXXXXXXXX");
        when(walletRepository.findByIdAndUserId(wallet.getId(), 42L)).thenReturn(Optional.of(wallet));
        when(bitcoinCoreProvider.getIfAvailable()).thenReturn(bitcoinCore);
        when(blockchainClientProvider.getIfAvailable()).thenReturn(mock(BlockchainClient.class));
        when(bitcoinAddressValidator.isValidBitcoinAddressForConfiguredNetwork(anyString())).thenReturn(true);
        when(addressDerivationService.deriveAddressFromXpub(anyString(), anyInt(), eq(true)))
                .thenReturn("tb1qchangeaddressfortestonlyxxxxxxxxxxxx");
        when(addressRepository.findByWalletIdAndStatusOrderByCreatedAtDesc(eq(wallet.getId()), any()))
                .thenReturn(List.of());
        // Live UTXO ownership for the requested input.
        BlockchainClient chain = mock(BlockchainClient.class);
        when(blockchainClientProvider.getIfAvailable()).thenReturn(chain);
        when(addressRepository.findByWalletIdAndStatusOrderByCreatedAtDesc(
                wallet.getId(), KfeWalletAddressStatus.ACTIVE)).thenReturn(List.of(address(wallet.getId())));
        when(chain.getUnspentOutputsMerged(anyString())).thenReturn(List.of(
                new BlockchainClient.AddressUtxo("txid-1", 0, 50_000L, "0014", 6, "tb1qinput")));
        when(bitcoinCore.createWatchOnlyPsbt(
                anyList(),
                eq(request.destinationAddress()),
                eq(request.amountSats()),
                eq(request.confirmationTarget()),
                isNull(),
                anyString())).thenReturn(new BitcoinCoreRpcClient.FundedPsbt("psbt-value", 250L));
        KfePsbtWorkflowEntity workflow = new KfePsbtWorkflowEntity();
        when(psbtWorkflowService.create(
                eq(42L),
                eq(wallet.getId()),
                eq("psbt-value"),
                eq(hashService.sha256("psbt-value")),
                eq(250L),
                eq(request.amountSats()),
                eq(request.destinationAddress()),
                anyList())).thenReturn(workflow);

        KfeColdWalletPsbtResponse response = service.createColdWalletPsbt(42L, wallet.getId(), request);

        assertThat(response.workflowId()).isEqualTo(workflow.getId());
        assertThat(response.psbt()).isEqualTo("psbt-value");
        assertThat(response.psbtHash()).isEqualTo(hashService.sha256("psbt-value"));
        assertThat(response.feeSats()).isEqualTo(250L);
        verify(transactionApprovalPort).approveColdWalletPsbt(eq(42L), any(DeviceProof.class));
        verify(auditLogService).record(
                eq("KFE_COLD_WALLET_PSBT_CREATED"),
                isNull(),
                eq(wallet.getId()),
                isNull(),
                isNull(),
                any());
    }

    private KfeWalletEntity wallet(KfeWalletKind kind) {
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(UUID.randomUUID());
        wallet.setUserId(42L);
        wallet.setKind(kind);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        wallet.setSpendable(kind != KfeWalletKind.WATCH_ONLY);
        wallet.setLabel("Treasury");
        return wallet;
    }

    private KfeWalletAddressEntity address(UUID walletId) {
        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setWalletId(walletId);
        address.setAddress("bc1qsourcexxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        address.setStatus(KfeWalletAddressStatus.ACTIVE);
        return address;
    }
}
