package com.kerosene.kfe.wallet.adapters.in.observation;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptPattern;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure helpers: extract addresses / funding inputs / structured fields from a raw Bitcoin
 * transaction. Used by the ZMQ rawtx path for instant mempool exposure (0 conf) without
 * waiting for scantxoutset.
 */
public final class KfeBitcoinZmqTxMatcher {

    /** Prevents instantiation of the stateless transaction parser. */
    private KfeBitcoinZmqTxMatcher() {
    }

    /**
     * Previous output consumed by a parsed transaction input.
     *
     * @param fundingTxid transaction ID of the funding transaction
     * @param vout zero-based output index consumed from that transaction
     */
    public record ParsedInput(String fundingTxid, long vout) {
    }

    /**
     * Address/value projection of one transaction output.
     *
     * @param address decoded destination address, or null for unsupported scripts
     * @param valueSats output value in satoshis
     * @param index zero-based transaction output index
     */
    public record ParsedOutput(String address, long valueSats, int index) {
    }

    /**
     * Parsed transaction identity and the input/output facts needed by wallet observers.
     *
     * @param txid canonical lowercase transaction ID
     * @param inputs parsed previous outpoints
     * @param outputs parsed output addresses, values, and indexes
     */
    public record ParsedRawTx(String txid, List<ParsedInput> inputs, List<ParsedOutput> outputs) {
        /** Copies parsed collections and treats absent lists as empty. */
        public ParsedRawTx {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
        }
    }

    /** Resolves bitcoinj network parameters, defaulting unknown labels to testnet parsing rules. */
    public static NetworkParameters networkParameters(String bitcoinNetwork) {
        String n = bitcoinNetwork == null ? "" : bitcoinNetwork.trim().toLowerCase(Locale.ROOT);
        return switch (n) {
            case "mainnet", "main" -> MainNetParams.get();
            case "regtest" -> RegTestParams.get();
            // testnet4 is not a first-class bitcoinj 0.15 network; TestNet3 params still
            // parse many bech32 test addresses well enough for filter matching.
            case "testnet", "testnet3", "testnet4", "signet" -> TestNet3Params.get();
            default -> TestNet3Params.get();
        };
    }

    /** Full parse for the instant mempool ingest path. */
    public static ParsedRawTx parse(byte[] rawTx, NetworkParameters params) {
        if (rawTx == null || rawTx.length == 0 || params == null) {
            return null;
        }
        try {
            Transaction tx = new Transaction(params, rawTx);
            String txid = tx.getTxId() != null
                    ? tx.getTxId().toString().toLowerCase(Locale.ROOT)
                    : tx.getHashAsString().toLowerCase(Locale.ROOT);
            if (txid == null || txid.isBlank()) {
                return null;
            }
            List<ParsedInput> inputs = new ArrayList<>();
            for (TransactionInput input : tx.getInputs()) {
                if (input == null || input.getOutpoint() == null || input.getOutpoint().getHash() == null) {
                    continue;
                }
                String hash = input.getOutpoint().getHash().toString();
                if (hash == null || hash.isBlank()) {
                    continue;
                }
                inputs.add(new ParsedInput(
                        hash.toLowerCase(Locale.ROOT),
                        Math.max(0L, input.getOutpoint().getIndex())));
            }
            List<ParsedOutput> outputs = new ArrayList<>();
            List<TransactionOutput> vouts = tx.getOutputs();
            for (int i = 0; i < vouts.size(); i++) {
                TransactionOutput output = vouts.get(i);
                String address = addressFromOutput(output, params);
                Coin value = output == null ? null : output.getValue();
                long sats = value == null ? 0L : Math.max(0L, value.getValue());
                if (address == null && sats <= 0L) {
                    continue;
                }
                outputs.add(new ParsedOutput(address, sats, i));
            }
            return new ParsedRawTx(txid, inputs, outputs);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** Extracts distinct output addresses from a raw transaction, or an empty set on parse failure. */
    public static Set<String> outputAddresses(byte[] rawTx, NetworkParameters params) {
        ParsedRawTx parsed = parse(rawTx, params);
        if (parsed == null || parsed.outputs().isEmpty()) {
            return Set.of();
        }
        Set<String> addresses = new LinkedHashSet<>();
        for (ParsedOutput output : parsed.outputs()) {
            if (output.address() != null && !output.address().isBlank()) {
                addresses.add(output.address());
            }
        }
        return addresses;
    }

    /**
     * Previous output txids spent by this transaction (funding txs). Used to detect Electrum
     * spends of cold inbounds without waiting for the scheduled scantxoutset cycle.
     */
    public static Set<String> inputFundingTxids(byte[] rawTx, NetworkParameters params) {
        ParsedRawTx parsed = parse(rawTx, params);
        if (parsed == null || parsed.inputs().isEmpty()) {
            return Set.of();
        }
        Set<String> funding = new LinkedHashSet<>();
        for (ParsedInput input : parsed.inputs()) {
            if (input.fundingTxid() != null && !input.fundingTxid().isBlank()) {
                funding.add(input.fundingTxid());
            }
        }
        return funding;
    }

    /** Decodes standard legacy and supported witness output scripts to a normalized address. */
    static String addressFromOutput(TransactionOutput output, NetworkParameters params) {
        if (output == null) {
            return null;
        }
        try {
            Script script = output.getScriptPubKey();
            if (script == null) {
                return null;
            }
            try {
                Address address = script.getToAddress(params, true);
                if (address != null) {
                    return address.toString().toLowerCase(Locale.ROOT);
                }
            } catch (RuntimeException ignored) {
                // fall through (includes ScriptException)
            }
            // bitcoinj 0.15: prefer pattern helpers that exist on this version.
            if (ScriptPattern.isP2PKH(script)) {
                return LegacyAddress.fromPubKeyHash(params, ScriptPattern.extractHashFromP2PKH(script))
                        .toString()
                        .toLowerCase(Locale.ROOT);
            }
            if (ScriptPattern.isP2SH(script)) {
                return LegacyAddress.fromScriptHash(params, ScriptPattern.extractHashFromP2SH(script))
                        .toString()
                        .toLowerCase(Locale.ROOT);
            }
            // Witness outputs: try Address.fromKey-less path via output helper when available.
            try {
                Address address = output.getAddressFromP2SH(params);
                if (address != null) {
                    return address.toString().toLowerCase(Locale.ROOT);
                }
            } catch (RuntimeException ignored) {
                // not P2SH
            }
            try {
                Address address = output.getAddressFromP2PKHScript(params);
                if (address != null) {
                    return address.toString().toLowerCase(Locale.ROOT);
                }
            } catch (RuntimeException ignored) {
                // not P2PKH
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }
}
