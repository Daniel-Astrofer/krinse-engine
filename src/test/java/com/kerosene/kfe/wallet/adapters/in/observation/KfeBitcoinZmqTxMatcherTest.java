package com.kerosene.kfe.wallet.adapters.in.observation;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.ScriptBuilder;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KfeBitcoinZmqTxMatcherTest {

    @Test
    void extractsOutputAddressesFromRawTransaction() {
        NetworkParameters params = TestNet3Params.get();
        ECKey key = new ECKey();
        LegacyAddress destination = LegacyAddress.fromKey(params, key);

        Transaction tx = new Transaction(params);
        // Dummy input so bitcoinj serializes a complete wire transaction.
        tx.addInput(org.bitcoinj.core.Sha256Hash.ZERO_HASH, 0L, ScriptBuilder.createEmpty());
        tx.addOutput(Coin.COIN, destination);
        byte[] raw = tx.bitcoinSerialize();

        Set<String> addresses = KfeBitcoinZmqTxMatcher.outputAddresses(raw, params);

        assertThat(addresses).isNotEmpty();
        assertThat(addresses).contains(destination.toString().toLowerCase());
    }

    @Test
    void parseExposesTxidInputsOutputsAndValues() {
        NetworkParameters params = TestNet3Params.get();
        LegacyAddress destination = LegacyAddress.fromKey(params, new ECKey());
        org.bitcoinj.core.Sha256Hash funding =
                org.bitcoinj.core.Sha256Hash.wrap(
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        Transaction tx = new Transaction(params);
        tx.addInput(funding, 1L, ScriptBuilder.createEmpty());
        tx.addOutput(Coin.valueOf(50_000L), destination);
        byte[] raw = tx.bitcoinSerialize();

        KfeBitcoinZmqTxMatcher.ParsedRawTx parsed = KfeBitcoinZmqTxMatcher.parse(raw, params);

        assertThat(parsed).isNotNull();
        assertThat(parsed.txid()).isNotBlank();
        assertThat(parsed.inputs()).hasSize(1);
        assertThat(parsed.inputs().get(0).fundingTxid()).isEqualTo(funding.toString());
        assertThat(parsed.inputs().get(0).vout()).isEqualTo(1L);
        assertThat(parsed.outputs()).isNotEmpty();
        assertThat(parsed.outputs().get(0).valueSats()).isEqualTo(50_000L);
        assertThat(parsed.outputs().get(0).address())
                .isEqualTo(destination.toString().toLowerCase());
    }

    @Test
    void emptyOrInvalidPayloadReturnsEmpty() {
        NetworkParameters params = KfeBitcoinZmqTxMatcher.networkParameters("testnet");
        assertThat(KfeBitcoinZmqTxMatcher.outputAddresses(null, params)).isEmpty();
        assertThat(KfeBitcoinZmqTxMatcher.outputAddresses(new byte[] {0x01, 0x02}, params)).isEmpty();
    }

    @Test
    void networkParametersMapMainnetAndTestnets() {
        assertThat(KfeBitcoinZmqTxMatcher.networkParameters("mainnet").getId())
                .isEqualTo(org.bitcoinj.params.MainNetParams.get().getId());
        assertThat(KfeBitcoinZmqTxMatcher.networkParameters("testnet4").getId())
                .isEqualTo(TestNet3Params.get().getId());
    }
}
