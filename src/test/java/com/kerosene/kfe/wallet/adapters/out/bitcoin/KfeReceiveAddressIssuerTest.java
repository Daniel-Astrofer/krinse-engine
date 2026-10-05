package com.kerosene.kfe.wallet.adapters.out.bitcoin;

import com.kerosene.kfe.wallet.adapters.out.persistence.KfeDerivationCursorService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import com.kerosene.common.service.AddressDerivationService;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KfeReceiveAddressIssuerTest {

    @Mock
    private AddressDerivationService addressDerivationService;

    @Mock
    private KfeDerivationCursorService cursorService;

    @Mock
    private ObjectProvider<BitcoinCoreRpcClient> rpcClientProvider;

    @Mock
    private BitcoinCoreRpcClient rpcClient;

    private KfeReceiveAddressIssuer issuerWithXpub;
    private KfeReceiveAddressIssuer issuerWithBitcoinCore;
    private KfeReceiveAddressIssuer issuerWithNone;

    @BeforeEach
    void setUp() {
        when(rpcClientProvider.getIfAvailable()).thenReturn(rpcClient);

        issuerWithXpub = new KfeReceiveAddressIssuer(
                addressDerivationService, cursorService, rpcClientProvider, "xpub123", false
        );

        issuerWithBitcoinCore = new KfeReceiveAddressIssuer(
                addressDerivationService, cursorService, rpcClientProvider, "", true
        );

        issuerWithNone = new KfeReceiveAddressIssuer(
                addressDerivationService, cursorService, rpcClientProvider, "", false
        );
    }

    @Test
    void canIssueReturnsTrueIfXpubProvided() {
        assertTrue(issuerWithXpub.canIssue());
    }

    @Test
    void canIssueReturnsTrueIfBitcoinCoreEnabled() {
        assertTrue(issuerWithBitcoinCore.canIssue());
    }

    @Test
    void canIssueReturnsFalseIfNeitherProvided() {
        KfeReceiveAddressIssuer none = new KfeReceiveAddressIssuer(
                addressDerivationService, cursorService, mock(ObjectProvider.class), "", false
        );
        assertFalse(none.canIssue());
    }

    @Test
    void issueWithXpubUsesDerivationService() {
        when(cursorService.nextIndex(KfeDerivationCursorService.KFE_BIP84_EXTERNAL)).thenReturn(5);
        when(addressDerivationService.deriveAddressDetailsFromXpub("xpub123", 5))
                .thenReturn(new AddressDerivationService.DerivedAddress("bc1qderived", new byte[0], 5, false));

        KfeReceiveAddressIssuer.IssuedAddress result = issuerWithXpub.issue("label");

        assertEquals("bc1qderived", result.address());
        assertEquals("m/84'/0'/0'/0/5", result.derivationPath());
        assertEquals(5, result.derivationIndex());
        assertTrue(result.providerReference().startsWith("KFE_PLATFORM_XPUB"));
    }

    @Test
    void issueWithBitcoinCoreUsesRpcClient() {
        when(rpcClient.getNewAddress("label")).thenReturn("bc1qcore");
        when(rpcClient.walletName()).thenReturn("wallet_test");

        KfeReceiveAddressIssuer.IssuedAddress result = issuerWithBitcoinCore.issue("label");

        assertEquals("bc1qcore", result.address());
        assertEquals("bitcoin-core-wallet:wallet_test", result.derivationPath());
        assertEquals(-1, result.derivationIndex());
        assertEquals("KFE_BITCOIN_CORE_WALLET", result.providerReference());
    }

    @Test
    void issueWithNoneThrowsException() {
        assertThrows(IllegalStateException.class, () -> issuerWithNone.issue("label"));
    }
}
