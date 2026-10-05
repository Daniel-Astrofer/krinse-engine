package com.kerosene.kfe.wallet.adapters.out.bitcoin;

import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.SegwitAddress;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BitcoinAddressValidatorTest {

    private static final byte[] HASH_160 = new byte[20];
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider = mock(ObjectProvider.class);

    @Test
    void acceptsChecksummedLegacyAndSegwitAddressesForConfiguredNetwork() {
        String mainnetLegacy = LegacyAddress.fromPubKeyHash(MainNetParams.get(), HASH_160).toString();
        String mainnetSegwit = SegwitAddress.fromHash(MainNetParams.get(), HASH_160).toString();
        BitcoinAddressValidator validator = validator("mainnet");

        assertThat(validator.isValidBitcoinAddressForConfiguredNetwork(mainnetLegacy)).isTrue();
        assertThat(validator.isValidBitcoinAddressForConfiguredNetwork(mainnetSegwit)).isTrue();
    }

    @Test
    void rejectsBadChecksumMixedCaseAndWrongNetwork() {
        String mainnetLegacy = LegacyAddress.fromPubKeyHash(MainNetParams.get(), HASH_160).toString();
        String mainnetSegwit = SegwitAddress.fromHash(MainNetParams.get(), HASH_160).toString();
        String testnetLegacy = LegacyAddress.fromPubKeyHash(TestNet3Params.get(), HASH_160).toString();
        BitcoinAddressValidator validator = validator("mainnet");

        assertThat(validator.isValidBitcoinAddressForConfiguredNetwork(mutateLastCharacter(mainnetLegacy))).isFalse();
        assertThat(validator.isValidBitcoinAddressForConfiguredNetwork(mixedCase(mainnetSegwit))).isFalse();
        assertThat(validator.isValidBitcoinAddressForConfiguredNetwork(testnetLegacy)).isFalse();
    }

    @Test
    void acceptsRegtestSegwitAndRejectsTestnetSegwitOnRegtest() {
        String regtest = SegwitAddress.fromHash(RegTestParams.get(), HASH_160).toString();
        String testnet = SegwitAddress.fromHash(TestNet3Params.get(), HASH_160).toString();
        BitcoinAddressValidator validator = validator("regtest");

        assertThat(validator.isValidBitcoinAddressForConfiguredNetwork(regtest)).isTrue();
        assertThat(validator.isValidBitcoinAddressForConfiguredNetwork(testnet)).isFalse();
    }

    @Test
    void delegatesNewerSegwitFormatsToConfiguredBitcoinCore() {
        String taproot = "bc1p9nh05ha8wrljf7ru236awm4t2x0d5ctkkywmu9sclnm4t0av2vgs4k3au7";
        BitcoinCoreRpcClient bitcoinCore = mock(BitcoinCoreRpcClient.class);
        when(bitcoinCoreProvider.getIfAvailable()).thenReturn(bitcoinCore);
        when(bitcoinCore.isValidAddress(taproot)).thenReturn(true);

        assertThat(validator("mainnet").isValidBitcoinAddressForConfiguredNetwork(taproot)).isTrue();
        verify(bitcoinCore).isValidAddress(taproot);
    }

    private BitcoinAddressValidator validator(String network) {
        return new BitcoinAddressValidator(network, bitcoinCoreProvider);
    }

    private String mutateLastCharacter(String address) {
        char last = address.charAt(address.length() - 1);
        return address.substring(0, address.length() - 1) + (last == '1' ? '2' : '1');
    }

    private String mixedCase(String address) {
        int index = address.indexOf('1') + 1;
        char value = Character.toUpperCase(address.charAt(index));
        return address.substring(0, index) + value + address.substring(index + 1);
    }
}
