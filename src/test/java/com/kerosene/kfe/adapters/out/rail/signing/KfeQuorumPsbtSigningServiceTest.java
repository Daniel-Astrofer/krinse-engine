package com.kerosene.kfe.adapters.out.rail.signing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kerosene.common.vaultmesh.settlement.VaultMeshPsbtReceipt;
import com.kerosene.common.vaultmesh.intent.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.settlement.VaultMeshSettlementPort;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;
import com.kerosene.kfe.adapters.out.rail.onchain.KfeOnchainPaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeQuorumPsbtSigningServiceTest {

    private static final String LOCAL_TXID = "11".repeat(32);
    private static final String MESH_TXID = "22".repeat(32);

    private final BitcoinCoreRpcClient bitcoinCore = mock(BitcoinCoreRpcClient.class);
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider = provider(bitcoinCore);
    private final KfeQuorumPsbtSigningService service = new KfeQuorumPsbtSigningService(
            bitcoinCoreProvider,
            mock(RestTemplate.class),
            new ObjectMapper(),
            null,   // vaultMeshSettlementPort
            false,  // meshOnly
            "USERS", // meshBucket
            1,
            6,
            "http://signer-one",
            "api-key",
            "signer-one",
            true,
            false,
            "bitcoin-core-wallet",
            "");     // signerTlsFingerprints

    @Test
    void rejectsFundedPsbtBeforeSigningWhenActualFeeExceedsReservedLimit() {
        when(bitcoinCore.createFundedPsbt("bcrt1qdestination", 100_000L, 6, null, "bech32"))
                .thenReturn(new BitcoinCoreRpcClient.FundedPsbt("funded-psbt", 500L));

        assertThatThrownBy(() -> service.preflight(command(499L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Funded PSBT fee exceeds configured on-chain fee cap.")
                .hasMessageContaining("actualFeeSats=500")
                .hasMessageContaining("capFeeSats=499");
    }

    @Test
    void acceptsFundedPsbtWhenActualFeeFitsReservedLimit() {
        when(bitcoinCore.createFundedPsbt("bcrt1qdestination", 100_000L, 6, null, "bech32"))
                .thenReturn(new BitcoinCoreRpcClient.FundedPsbt("funded-psbt", 500L));

        var preflight = service.preflight(command(500L));

        assertThat(preflight.feeSats()).isEqualTo(500L);
        assertThat(preflight.configuredSignerCount()).isEqualTo(1);
    }

    @Test
    void localCoreSignerAloneSatisfiesQuorumWhenEnabled() {
        KfeQuorumPsbtSigningService localOnly = new KfeQuorumPsbtSigningService(
                bitcoinCoreProvider,
                mock(RestTemplate.class),
                new ObjectMapper(),
                null,   // vaultMeshSettlementPort
                false,  // meshOnly
                "USERS", // meshBucket
                1,
                6,
                "",
                "",
                "",
                false,
                true,
                "bitcoin-core-wallet",
                "");     // signerTlsFingerprints

        when(bitcoinCore.createFundedPsbt(
                        org.mockito.ArgumentMatchers.eq("bcrt1qdestination"),
                        org.mockito.ArgumentMatchers.eq(100_000L),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("bech32"),
                        org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(new BitcoinCoreRpcClient.FundedPsbt("funded-psbt", 200L));
        when(bitcoinCore.walletProcessPsbt("funded-psbt")).thenReturn("signed-psbt");
        when(bitcoinCore.combinePsbt(org.mockito.ArgumentMatchers.anyList())).thenReturn("combined-psbt");
        when(bitcoinCore.finalizePsbt("combined-psbt"))
                .thenReturn(new BitcoinCoreRpcClient.FinalizedPsbt("deadbeef", true));
        when(bitcoinCore.sendRawTransaction("deadbeef")).thenReturn(LOCAL_TXID);
        // PSBT integrity check mocks
        ObjectNode decodedTx = (ObjectNode) decodedPsbtNode("bcrt1qdestination", 100_000L, LOCAL_TXID);
        ObjectNode signedTx = decodedTx.deepCopy();
        addPartialSignature(signedTx, "02" + "33".repeat(32));
        when(bitcoinCore.decodePsbt("funded-psbt")).thenReturn(decodedTx);
        when(bitcoinCore.decodePsbt("signed-psbt")).thenReturn(signedTx);
        when(bitcoinCore.decodePsbt("combined-psbt")).thenReturn(signedTx);
        when(bitcoinCore.decodeRawTransaction("deadbeef")).thenReturn(decodedTx.get("tx"));
        when(bitcoinCore.testMempoolAccept("deadbeef")).thenReturn(mempoolAccept(true));

        var execution = localOnly.execute(new KfeOnchainPaymentGateway.OnchainPaymentCommand(
                42L,
                null,
                "wallet",
                "bcrt1qdestination",
                100_000L,
                500L,
                "memo",
                "idem",
                "proof"));

        assertThat(execution.txid()).isEqualTo(LOCAL_TXID);
        assertThat(execution.acceptedSigners()).singleElement()
                .asString().startsWith("bitcoin-core-wallet#");
    }

    @Test
    void rejectsWhenNoSignerSeatsConfigured() {
        KfeQuorumPsbtSigningService none = new KfeQuorumPsbtSigningService(
                bitcoinCoreProvider,
                mock(RestTemplate.class),
                new ObjectMapper(),
                null,   // vaultMeshSettlementPort
                false,  // meshOnly
                "USERS", // meshBucket
                1,
                6,
                "",
                "",
                "",
                true,
                false,
                "bitcoin-core-wallet",
                "");     // signerTlsFingerprints

        assertThatThrownBy(() -> none.preflight(command(500L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No quorum PSBT signer");
    }

    @Test
    void meshOnlyUsesVaultMeshSignerAndSkipsLocalCore() {
        VaultMeshSettlementPort mesh = new VaultMeshSettlementPort() {
            @Override
            public VaultMeshReceipt submitIntent(com.kerosene.common.vaultmesh.intent.VaultMeshIntent intent) {
                return new VaultMeshReceipt(
                        intent.intentId(), VaultMeshReceipt.Status.REJECTED, "UNUSED", null, Instant.ofEpochMilli(1L));
            }

            @Override
            public VaultMeshPsbtReceipt signPsbt(com.kerosene.common.vaultmesh.settlement.VaultMeshPsbtRequest request) {
                return new VaultMeshPsbtReceipt(
                        request.intentId(),
                        VaultMeshReceipt.Status.ACCEPTED,
                        null,
                        "mesh-signed-psbt",
                        "sig-proof",
                        Instant.ofEpochMilli(1L));
            }
        };
        KfeQuorumPsbtSigningService meshOnly = new KfeQuorumPsbtSigningService(
                bitcoinCoreProvider,
                mock(RestTemplate.class),
                new ObjectMapper(),
                mesh,
                true,
                "USERS",
                2,
                6,
                "",
                "",
                "",
                false,
                true,
                "bitcoin-core-wallet",
                "");     // signerTlsFingerprints

        when(bitcoinCore.createFundedPsbt(
                        org.mockito.ArgumentMatchers.eq("tb1qdestination"),
                        org.mockito.ArgumentMatchers.eq(50_000L),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("bech32m"),
                        org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(new BitcoinCoreRpcClient.FundedPsbt("funded-psbt", 150L));
        when(bitcoinCore.finalizePsbt("mesh-signed-psbt"))
                .thenReturn(new BitcoinCoreRpcClient.FinalizedPsbt("cafebabe", true));
        when(bitcoinCore.sendRawTransaction("cafebabe")).thenReturn(MESH_TXID);
        // PSBT integrity check mocks
        JsonNode decodedTx = decodedPsbtNode("tb1qdestination", 50_000L, MESH_TXID);
        when(bitcoinCore.decodePsbt("funded-psbt")).thenReturn(decodedTx);
        when(bitcoinCore.decodePsbt("mesh-signed-psbt")).thenReturn(decodedTx);
        when(bitcoinCore.decodeRawTransaction("cafebabe")).thenReturn(decodedTx.get("tx"));
        when(bitcoinCore.testMempoolAccept("cafebabe")).thenReturn(mempoolAccept(true));

        var execution = meshOnly.execute(new KfeOnchainPaymentGateway.OnchainPaymentCommand(
                7L,
                null,
                "wallet",
                "tb1qdestination",
                50_000L,
                500L,
                "memo",
                "idem-mesh",
                "proof"));

        assertThat(execution.txid()).isEqualTo(MESH_TXID);
        assertThat(execution.acceptedSigners()).containsExactly("vault-mesh");
    }

    @Test
    void knownPreparedTransactionIsReconciledWithoutAnotherBroadcast() {
        ObjectNode decoded = new ObjectMapper().createObjectNode();
        decoded.put("txid", LOCAL_TXID);
        when(bitcoinCore.decodeRawTransaction("deadbeef")).thenReturn(decoded);
        when(bitcoinCore.findTransactionConfirmations(LOCAL_TXID)).thenReturn(OptionalInt.of(0));
        KfeOnchainPaymentGateway.PreparedOnchainPayment prepared =
                new KfeOnchainPaymentGateway.PreparedOnchainPayment(
                        "deadbeef",
                        LOCAL_TXID,
                        200L,
                        "funded-hash",
                        "combined-hash",
                        sha256("deadbeef"),
                        List.of("bitcoin-core-wallet"),
                        "intent-id",
                        "{}");

        KfeQuorumPsbtSigningService.OnchainExecution execution = service.broadcast(prepared);

        assertThat(execution.txid()).isEqualTo(LOCAL_TXID);
        verify(bitcoinCore, never()).requireMempoolAccept("deadbeef");
        verify(bitcoinCore, never()).sendRawTransaction("deadbeef");
    }

    @Test
    void rejectsTwoRemoteSeatsThatReturnTheSameCryptographicSignerKey() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        KfeQuorumPsbtSigningService twoSeats = new KfeQuorumPsbtSigningService(
                bitcoinCoreProvider,
                restTemplate,
                new ObjectMapper(),
                null,
                false,
                "USERS",
                2,
                6,
                "http://signer-one,http://signer-two",
                "key-one,key-two",
                "signer-one,signer-two",
                true,
                false,
                "bitcoin-core-wallet",
                "");
        when(bitcoinCore.createFundedPsbt(
                        org.mockito.ArgumentMatchers.eq("bcrt1qdestination"),
                        org.mockito.ArgumentMatchers.eq(100_000L),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("bech32"),
                        org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(new BitcoinCoreRpcClient.FundedPsbt("funded-psbt", 200L));
        when(restTemplate.postForEntity(
                        org.mockito.ArgumentMatchers.eq("http://signer-one"),
                        org.mockito.ArgumentMatchers.any(HttpEntity.class),
                        org.mockito.ArgumentMatchers.eq(String.class)))
                .thenReturn(ResponseEntity.ok(
                        "{\"signerId\":\"signer-one\",\"signedPsbt\":\"partial-one\"}"));
        when(restTemplate.postForEntity(
                        org.mockito.ArgumentMatchers.eq("http://signer-two"),
                        org.mockito.ArgumentMatchers.any(HttpEntity.class),
                        org.mockito.ArgumentMatchers.eq(String.class)))
                .thenReturn(ResponseEntity.ok(
                        "{\"signerId\":\"signer-two\",\"signedPsbt\":\"partial-two\"}"));

        ObjectNode funded = (ObjectNode) decodedPsbtNode("bcrt1qdestination", 100_000L, LOCAL_TXID);
        ObjectNode first = funded.deepCopy();
        ObjectNode second = funded.deepCopy();
        String repeatedPublicKey = "02" + "44".repeat(32);
        addPartialSignature(first, repeatedPublicKey);
        addPartialSignature(second, repeatedPublicKey);
        when(bitcoinCore.decodePsbt("funded-psbt")).thenReturn(funded);
        when(bitcoinCore.decodePsbt("partial-one")).thenReturn(first);
        when(bitcoinCore.decodePsbt("partial-two")).thenReturn(second);

        assertThatThrownBy(() -> twoSeats.prepare(new KfeOnchainPaymentGateway.OnchainPaymentCommand(
                        42L,
                        null,
                        "wallet",
                        "bcrt1qdestination",
                        100_000L,
                        500L,
                        "memo",
                        "duplicate-key",
                        "proof")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 of 2 distinct cryptographic signers");

        verify(bitcoinCore, never()).combinePsbt(org.mockito.ArgumentMatchers.anyList());
    }

    private KfeOnchainPaymentGateway.OnchainPreflightCommand command(long maxFeeSats) {
        return new KfeOnchainPaymentGateway.OnchainPreflightCommand(
                42L,
                null,
                "wallet",
                "bcrt1qdestination",
                100_000L,
                maxFeeSats,
                "idempotency-key");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Creates a minimal valid-looking decoded PSBT JSON node with the expected
     * destination and amount for test assertions.
     */
    static JsonNode decodedPsbtNode(String destinationAddress, long amountSats, String txid) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode tx = mapper.createObjectNode();
        tx.put("txid", txid);
        tx.put("version", 2);
        tx.put("locktime", 0);

        ArrayNode vin = mapper.createArrayNode();
        ObjectNode input = mapper.createObjectNode();
        input.put("txid", "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
        input.put("vout", 0);
        input.put("sequence", 4294967293L);
        vin.add(input);
        tx.set("vin", vin);

        ArrayNode vout = mapper.createArrayNode();
        ObjectNode output = mapper.createObjectNode();
        output.put("value", BigDecimal.valueOf(amountSats).divide(BigDecimal.valueOf(100_000_000)));
        output.put("n", 0);
        ObjectNode spk = mapper.createObjectNode();
        spk.put("address", destinationAddress);
        output.set("scriptPubKey", spk);
        vout.add(output);
        tx.set("vout", vout);

        root.set("tx", tx);

        // Add minimal witness_utxo for fee computation
        ArrayNode inputs = mapper.createArrayNode();
        ObjectNode psbtInput = mapper.createObjectNode();
        ObjectNode witnessUtxo = mapper.createObjectNode();
        // Fee = 200 sats for simplicity; input amount = output amount + fee
        witnessUtxo.put("amount",
                BigDecimal.valueOf(amountSats + 200L).divide(BigDecimal.valueOf(100_000_000)));
        psbtInput.set("witness_utxo", witnessUtxo);
        inputs.add(psbtInput);
        root.set("inputs", inputs);

        return root;
    }

    private static void addPartialSignature(ObjectNode decodedPsbt, String publicKey) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode inputs = (ArrayNode) decodedPsbt.withArray("inputs");
        ObjectNode input = inputs.isEmpty()
                ? mapper.createObjectNode()
                : (ObjectNode) inputs.get(0);
        ObjectNode signatures = mapper.createObjectNode();
        signatures.put(publicKey, "30440220");
        input.set("partial_signatures", signatures);
        if (inputs.isEmpty()) {
            inputs.add(input);
        }
    }

    static JsonNode mempoolAccept(boolean allowed) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode arr = mapper.createArrayNode();
        ObjectNode entry = mapper.createObjectNode();
        entry.put("txid", "0000000000000000000000000000000000000000000000000000000000000000");
        entry.put("allowed", allowed);
        if (!allowed) {
            entry.put("reject-reason", "test-reject");
        }
        arr.add(entry);
        return arr;
    }
}
