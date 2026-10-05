package com.kerosene.kfe.adapters.out.integration.wallet;

import com.kerosene.common.exception.FinancialProviderUnavailableException;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeCreateWalletRequest;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeWalletResponse;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.wallet.adapters.in.KfeWalletService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeFinancialWalletProvisioningAdapterTest {

    private static final Long USER_ID = 42L;
    private static final String NOT_READY = "Primary KFE wallet is not ready.";

    private final KfeWalletService walletService = mock(KfeWalletService.class);
    private final KfeFinancialWalletProvisioningAdapter adapter =
            new KfeFinancialWalletProvisioningAdapter(walletService);

    @Test
    void existingActiveSpendableInternalWalletIsReadyWithoutAnotherCreation() {
        when(walletService.listWallets(USER_ID)).thenReturn(List.of(readyWallet()));

        adapter.ensurePrimaryWalletReady(USER_ID, "unused-address");

        verify(walletService, never()).createWallet(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = KfeWalletStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void existingInternalWalletInOtherStatusIsUnavailableWithoutAnotherCreation(KfeWalletStatus status) {
        when(walletService.listWallets(USER_ID)).thenReturn(List.of(wallet(KfeWalletKind.INTERNAL, status, true)));

        FinancialProviderUnavailableException failure = assertThrows(FinancialProviderUnavailableException.class,
                () -> adapter.ensurePrimaryWalletReady(USER_ID, null));

        assertEquals(NOT_READY, failure.getMessage());
        verify(walletService, never()).createWallet(any(), any());
    }

    @Test
    void existingActiveButUnspendableInternalWalletIsUnavailableWithoutAnotherCreation() {
        when(walletService.listWallets(USER_ID)).thenReturn(List.of(
                wallet(KfeWalletKind.INTERNAL, KfeWalletStatus.ACTIVE, false)));

        assertThrows(FinancialProviderUnavailableException.class,
                () -> adapter.ensurePrimaryWalletReady(USER_ID, null));

        verify(walletService, never()).createWallet(any(), any());
    }

    @Test
    void readyOtherKindDoesNotMaskAnInternalWalletStillCreating() {
        when(walletService.listWallets(USER_ID)).thenReturn(List.of(
                wallet(KfeWalletKind.CUSTODIAL_ONCHAIN, KfeWalletStatus.ACTIVE, true),
                wallet(KfeWalletKind.INTERNAL, KfeWalletStatus.CREATING, true)));

        assertThrows(FinancialProviderUnavailableException.class,
                () -> adapter.ensurePrimaryWalletReady(USER_ID, null));

        verify(walletService, never()).createWallet(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = KfeWalletKind.class, names = "INTERNAL", mode = EnumSource.Mode.EXCLUDE)
    void otherWalletKindDoesNotCountAsPrimary(KfeWalletKind kind) {
        when(walletService.listWallets(USER_ID)).thenReturn(List.of(wallet(kind, KfeWalletStatus.ACTIVE, true)));
        when(walletService.createWallet(eq(USER_ID), any())).thenReturn(readyWallet());

        adapter.ensurePrimaryWalletReady(USER_ID, null);

        assertEquals(KfeWalletKind.INTERNAL, createdRequest().kind());
    }

    @Test
    void missingPrimaryCreatesAnInternalWalletWithNormalizedInitialAddress() {
        when(walletService.listWallets(USER_ID)).thenReturn(List.of());
        when(walletService.createWallet(eq(USER_ID), any())).thenReturn(readyWallet());

        adapter.ensurePrimaryWalletReady(USER_ID, "  signup-deposit-address  ");

        KfeCreateWalletRequest request = createdRequest();
        assertEquals(KfeWalletKind.INTERNAL, request.kind());
        assertEquals("Conta Assegurada", request.label());
        assertEquals("signup-deposit-address", request.initialAddress());
        assertEquals("SIGNUP_STATE_DEPOSIT_ADDRESS", request.initialAddressProviderReference());
        assertFalse(request.issueInitialAddress());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void blankInitialAddressHasNoDepositAddressReference(String initialAddress) {
        when(walletService.listWallets(USER_ID)).thenReturn(List.of());
        when(walletService.createWallet(eq(USER_ID), any())).thenReturn(readyWallet());

        adapter.ensurePrimaryWalletReady(USER_ID, initialAddress);

        KfeCreateWalletRequest request = createdRequest();
        assertNull(request.initialAddress());
        assertNull(request.initialAddressProviderReference());
        assertFalse(request.issueInitialAddress());
    }

    @ParameterizedTest
    @EnumSource(value = KfeWalletStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void createdInternalWalletMustBeActiveBeforeSuccess(KfeWalletStatus status) {
        when(walletService.listWallets(USER_ID)).thenReturn(List.of());
        when(walletService.createWallet(eq(USER_ID), any()))
                .thenReturn(wallet(KfeWalletKind.INTERNAL, status, true));

        FinancialProviderUnavailableException failure = assertThrows(FinancialProviderUnavailableException.class,
                () -> adapter.ensurePrimaryWalletReady(USER_ID, null));

        assertEquals(NOT_READY, failure.getMessage());
        createdRequest();
    }

    @Test
    void createdActiveInternalWalletMustAlsoBeSpendable() {
        when(walletService.listWallets(USER_ID)).thenReturn(List.of());
        when(walletService.createWallet(eq(USER_ID), any()))
                .thenReturn(wallet(KfeWalletKind.INTERNAL, KfeWalletStatus.ACTIVE, false));

        assertThrows(FinancialProviderUnavailableException.class,
                () -> adapter.ensurePrimaryWalletReady(USER_ID, null));

        createdRequest();
    }

    @Test
    void createdWalletMustBeInternal() {
        when(walletService.listWallets(USER_ID)).thenReturn(List.of());
        when(walletService.createWallet(eq(USER_ID), any()))
                .thenReturn(wallet(KfeWalletKind.CUSTODIAL_ONCHAIN, KfeWalletStatus.ACTIVE, true));

        assertThrows(FinancialProviderUnavailableException.class,
                () -> adapter.ensurePrimaryWalletReady(USER_ID, null));

        createdRequest();
    }

    @Test
    void missingCreationResultIsUnavailable() {
        when(walletService.listWallets(USER_ID)).thenReturn(List.of());
        when(walletService.createWallet(eq(USER_ID), any())).thenReturn(null);

        assertThrows(FinancialProviderUnavailableException.class,
                () -> adapter.ensurePrimaryWalletReady(USER_ID, null));

        createdRequest();
    }

    @Test
    void creationFailureIsPropagatedWithoutRetry() {
        when(walletService.listWallets(USER_ID)).thenReturn(List.of());
        FinancialProviderUnavailableException cause =
                new FinancialProviderUnavailableException("Quorum unavailable.");
        when(walletService.createWallet(eq(USER_ID), any())).thenThrow(cause);

        assertSame(cause, assertThrows(FinancialProviderUnavailableException.class,
                () -> adapter.ensurePrimaryWalletReady(USER_ID, null)));

        createdRequest();
    }

    @Test
    void lookupFailureIsPropagatedWithoutCreatingWallet() {
        IllegalStateException cause = new IllegalStateException("Wallet lookup unavailable.");
        when(walletService.listWallets(USER_ID)).thenThrow(cause);

        assertSame(cause, assertThrows(IllegalStateException.class,
                () -> adapter.ensurePrimaryWalletReady(USER_ID, null)));

        verify(walletService, never()).createWallet(any(), any());
    }

    private KfeCreateWalletRequest createdRequest() {
        ArgumentCaptor<KfeCreateWalletRequest> request = ArgumentCaptor.forClass(KfeCreateWalletRequest.class);
        verify(walletService).createWallet(eq(USER_ID), request.capture());
        return request.getValue();
    }

    private KfeWalletResponse readyWallet() {
        return wallet(KfeWalletKind.INTERNAL, KfeWalletStatus.ACTIVE, true);
    }

    private KfeWalletResponse wallet(KfeWalletKind kind, KfeWalletStatus status, boolean spendable) {
        return new KfeWalletResponse(UUID.randomUUID(), kind, status, "Conta Assegurada", null,
                null, "BTC", spendable, false, false, null, null, null);
    }
}
