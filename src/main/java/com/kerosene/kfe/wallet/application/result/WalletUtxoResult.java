package com.kerosene.kfe.wallet.application.result;

import com.kerosene.kfe.wallet.domain.model.UtxoSnapshot;

import java.util.List;

/**
 * Immutable application result for a wallet's observed unspent outputs.
 *
 * @param utxos chain observations; null input is normalized to an empty immutable list
 */
public record WalletUtxoResult(
        List<UtxoSnapshot> utxos) {
    /** Copies the output collection, treating null as empty to preserve an immutable result. */
    public WalletUtxoResult {
        utxos = utxos == null ? List.of() : List.copyOf(utxos);
    }
}
