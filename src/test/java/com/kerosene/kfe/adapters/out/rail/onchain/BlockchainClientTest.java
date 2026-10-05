package com.kerosene.kfe.adapters.out.rail.onchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockchainClientTest {

    @Test
    void hotWalletBalanceUsesExactSatoshiConversion() {
        StubBlockchainClient client = new StubBlockchainClient(balances("0.00000001", "0.00000002", "0.00000000"));

        assertEquals(3L, client.getHotWalletBalance());
    }

    @Test
    void hotWalletBalanceFailsClosedForSubSatoshiRpcAmounts() {
        StubBlockchainClient client = new StubBlockchainClient(balances("0.000000001", "0.00000000", "0.00000000"));

        assertEquals(0L, client.getHotWalletBalance());
    }

    @Test
    void unspentOutputsUseExactSatoshiConversion() {
        ObjectNode response = result(array(utxo("tx-1", 0, "0.00000001", 2)));
        StubBlockchainClient client = new StubBlockchainClient(response);

        List<BlockchainClient.AddressUtxo> utxos = client.getUnspentOutputs("bc1qaddress");

        assertEquals(1, utxos.size());
        assertEquals(1L, utxos.getFirst().valueSats());
        assertEquals(2, utxos.getFirst().confirmations());
        assertEquals("bc1qaddress", utxos.getFirst().address());
    }

    @Test
    void unspentOutputsFailClosedForSubSatoshiRpcAmounts() {
        ObjectNode response = result(array(utxo("tx-1", 0, "0.000000001", 0)));
        StubBlockchainClient client = new StubBlockchainClient(response);

        assertTrue(client.getUnspentOutputs("bc1qaddress").isEmpty());
    }

    @Test
    void scantxoutsetUnspentsDeriveConfirmationsFromHeight() {
        ObjectNode unspent = JsonNodeFactory.instance.objectNode();
        unspent.put("txid", "scan-tx");
        unspent.put("vout", 1);
        unspent.set("amount", decimal("0.00010000"));
        unspent.put("height", 100);
        unspent.put("scriptPubKey", "0014ab");
        ObjectNode scan = JsonNodeFactory.instance.objectNode();
        scan.put("height", 105);
        scan.set("unspents", array(unspent));
        StubBlockchainClient client = new StubBlockchainClient(result(scan));

        List<BlockchainClient.AddressUtxo> utxos = client.getUnspentOutputsFromScan("bc1qaddress", 1);

        assertEquals(1, utxos.size());
        assertEquals("scan-tx", utxos.getFirst().txid());
        assertEquals(10_000L, utxos.getFirst().valueSats());
        assertEquals(6, utxos.getFirst().confirmations()); // tip 105 - height 100 + 1
    }

    private static ObjectNode balances(String trusted, String pending, String immature) {
        ObjectNode mine = JsonNodeFactory.instance.objectNode();
        mine.set("trusted", decimal(trusted));
        mine.set("untrusted_pending", decimal(pending));
        mine.set("immature", decimal(immature));

        ObjectNode balances = JsonNodeFactory.instance.objectNode();
        balances.set("mine", mine);
        return result(balances);
    }

    private static ObjectNode utxo(String txid, int vout, String amount, int confirmations) {
        ObjectNode utxo = JsonNodeFactory.instance.objectNode();
        utxo.put("txid", txid);
        utxo.put("vout", vout);
        utxo.set("amount", decimal(amount));
        utxo.put("scriptPubKey", "0014");
        utxo.put("confirmations", confirmations);
        return utxo;
    }

    private static ArrayNode array(JsonNode node) {
        return JsonNodeFactory.instance.arrayNode().add(node);
    }

    private static DecimalNode decimal(String value) {
        return DecimalNode.valueOf(new BigDecimal(value));
    }

    private static ObjectNode result(JsonNode value) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.set("result", value);
        return response;
    }

    private static final class StubBlockchainClient implements BlockchainClient {
        private final JsonNode response;

        private StubBlockchainClient(JsonNode response) {
            this.response = response;
        }

        @Override
        public JsonNode executeRpc(String method, Object... params) {
            return response;
        }

        @Override
        public String sendRawTransaction(String hex) {
            return null;
        }

        @Override
        public JsonNode getRawTransaction(String txid, boolean verbose) {
            return JsonNodeFactory.instance.objectNode();
        }
    }
}
