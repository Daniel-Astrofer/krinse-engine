package com.kerosene.kfe.paymentexecution.adapters.out.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaExecutionCommandStoreAdapter;
import com.kerosene.kfe.paymentexecution.application.command.ScheduleExternalExecutionCommand;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionIntentBinding;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ExecutionEnvelopeReaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID TRANSACTION = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID SOURCE = UUID.fromString("11111111-aaaa-bbbb-cccc-222222222222");
    private static final UUID DESTINATION = UUID.fromString("99999999-aaaa-bbbb-cccc-888888888888");
    private static final KfeHashService HASH = new KfeHashService();
    private final ExecutionEnvelopeReader reader = new ExecutionEnvelopeReader();

    @ParameterizedTest
    @EnumSource(PaymentRail.class)
    void readsTheRealProducerWithTheRealRawPayloadHash(PaymentRail rail) {
        KfeExecutionOutboxRepository repository = mock(KfeExecutionOutboxRepository.class);
        var producer = new JpaExecutionCommandStoreAdapter(repository, HASH, JSON);
        var command = new ScheduleExternalExecutionCommand(new PaymentExecutionId(TRANSACTION),
                new IdempotencyKey("private-idempotency"), 42L, rail, PaymentDirection.OUTBOUND,
                SOURCE, DESTINATION, 99_000L, 1_000L, 101_000L, "private-destination",
                "private-memo: café ☕", "private-proposal", 12L, 3);

        UUID outboxId = producer.enqueue(command);

        var capture = ArgumentCaptor.forClass(KfeExecutionOutboxEntity.class);
        verify(repository).save(capture.capture());
        KfeExecutionOutboxEntity outbox = capture.getValue();
        assertThat(outboxId).isEqualTo(outbox.getId());
        assertThat(outbox.getPayloadHash()).isEqualTo(HASH.sha256(outbox.getPayloadJson()));
        var envelope = reader.read(outbox.getPayloadJson(), outbox.getPayloadHash());
        assertThat(envelope.binding()).isEqualTo(new ExecutionIntentBinding(TRANSACTION, 42L,
                "private-idempotency", rail, PaymentDirection.OUTBOUND, SOURCE, DESTINATION,
                99_000L, 1_000L, 101_000L, "private-destination", "private-memo: café ☕",
                "private-proposal"));
        assertThat(envelope.feeRateSatsPerVbyte()).isEqualTo(12L);
        assertThat(envelope.feeTargetBlocks()).isEqualTo(3);
        assertThat(envelope.estimatedVbytes()).isNull();
        assertThat(envelope.quoteId()).isNull();
    }

    @Test
    void readsRealProducerWithoutOptionalHintsOrDestination() {
        KfeExecutionOutboxRepository repository = mock(KfeExecutionOutboxRepository.class);
        new JpaExecutionCommandStoreAdapter(repository, HASH, JSON).enqueue(
                new ScheduleExternalExecutionCommand(new PaymentExecutionId(TRANSACTION),
                        new IdempotencyKey("key"), 42L, PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND,
                        SOURCE, null, 100L, 0L, 101L, "invoice", null, "proposal", null, null));
        var capture = ArgumentCaptor.forClass(KfeExecutionOutboxEntity.class);
        verify(repository).save(capture.capture());
        var stored = capture.getValue();
        var envelope = reader.read(stored.getPayloadJson(), stored.getPayloadHash());
        assertThat(envelope.binding().destinationWalletId()).isNull();
        assertThat(envelope.binding().memo()).isNull();
        assertThat(envelope.feeRateSatsPerVbyte()).isNull();
        assertThat(envelope.feeTargetBlocks()).isNull();
    }

    @Test
    void hashesTheRawUtf8JsonAndAcceptsUppercaseHashHex() {
        String json = " \n" + valid().put("memo", "café ☕").toString() + "\t";
        assertThat(reader.read(json, HASH.sha256(json).toUpperCase(Locale.ROOT)).binding().memo())
                .isEqualTo("café ☕");
        assertInvalid(json, HASH.sha256(json.trim()));
        assertInvalid(json + " ", HASH.sha256(json));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", " 0123456789abcdef", "hash-with-private-data",
            "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg",
            "0000000000000000000000000000000000000000000000000000000000000000"})
    void rejectsInvalidOrMismatchedHashesWithoutDiagnosticPayload(String hash) {
        assertInvalid(valid().toString(), hash);
    }

    @Test
    void rejectsNullHashAndPayloadAndHashWhitespace() {
        String json = valid().toString();
        assertInvalid(json, null);
        assertInvalid(null, HASH.sha256(""));
        assertInvalid(json, " " + HASH.sha256(json));
        assertInvalid(json, HASH.sha256(json) + "\n");
    }

    @ParameterizedTest(name = "rejects required {0} = {1}")
    @MethodSource("invalidRequiredFields")
    void requiresExactBaseFieldTypes(String name, String rawValue) throws Exception {
        ObjectNode payload = valid();
        if (rawValue == null) {
            payload.remove(name);
        } else {
            payload.set(name, JSON.readTree(rawValue));
        }
        assertInvalid(payload.toString());
    }

    static Stream<Arguments> invalidRequiredFields() {
        Stream<Arguments> strings = Stream.of("transactionId", "idempotencyKey", "rail", "direction",
                        "quorumProposalHash")
                .flatMap(field -> Arrays.asList(null, "null", "123", "true", "{}", "[]").stream()
                        .map(value -> Arguments.of(field, value)));
        Stream<Arguments> numbers = Stream.of("userId", "amountSats", "networkFeeSats", "totalDebitSats")
                .flatMap(field -> Arrays.asList(null, "null", "\"1\"", "true", "1.0", "1e0", "{}", "[]",
                                "9223372036854775808", "-9223372036854775809").stream()
                        .map(value -> Arguments.of(field, value)));
        return Stream.concat(strings, numbers);
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE})
    void preservesExactLongValuesForTheDomainPolicyToValidate(long value) {
        var envelope = read(valid().put("userId", value).put("amountSats", value)
                .put("networkFeeSats", value).put("totalDebitSats", value).toString());
        assertThat(envelope.binding().userId()).isEqualTo(value);
        assertThat(envelope.binding().amountSats()).isEqualTo(value);
        assertThat(envelope.binding().networkFeeSats()).isEqualTo(value);
        assertThat(envelope.binding().totalDebitSats()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sourceWalletId", "destinationWalletId", "externalReference", "memo", "quoteId"})
    void acceptsAbsentOrNullOptionalFieldsButNeverCoercesThem(String field) {
        ObjectNode payload = valid();
        payload.remove(field);
        read(payload.toString());
        payload.putNull(field);
        read(payload.toString());
        assertInvalid(payload.put(field, 1).toString());
        assertInvalid(payload.put(field, true).toString());
        payload.set(field, JSON.createObjectNode());
        assertInvalid(payload.toString());
        payload.set(field, JSON.createArrayNode());
        assertInvalid(payload.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"transactionId", "sourceWalletId", "destinationWalletId"})
    void requiresCanonicalUuidButAcceptsHexCase(String field) {
        var payload = valid().put(field, TRANSACTION.toString().toUpperCase(Locale.ROOT));
        read(payload.toString());
        for (String invalid : List.of("1-1-1-1-1", "", "private-invalid-uuid", " " + TRANSACTION,
                TRANSACTION + " ", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeee", TRANSACTION + "f")) {
            assertInvalid(payload.put(field, invalid).toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"onchain", " ONCHAIN", "ONCHAIN ", "PRIVATE_UNKNOWN", ""})
    void rejectsRailNamesThatAreNotExactWireEnums(String value) {
        assertInvalid(valid().put("rail", value).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"outbound", " OUTBOUND", "OUTBOUND ", "PRIVATE_UNKNOWN", ""})
    void rejectsDirectionNamesThatAreNotExactWireEnums(String value) {
        assertInvalid(valid().put("direction", value).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "null", "[]", "1", "true", "\"private-secret\"", "{",
            "{'transactionId':'private-secret'}", "{unquoted:1}", "{\"x\":NaN}",
            "{\"x\":Infinity}", "{\"x\":01}", "{\"x\":+1}", "{\"x\":1,}",
            "{\"x\":\"private\nsecret\"}", "{\"x\":\"\\q\"}"})
    void rejectsMalformedAndNonObjectJsonEvenWithMatchingHash(String json) {
        assertInvalid(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {" {}", " null", " []", " true", " 123", " private-secret",
            " /*private-secret*/", " //private-secret"})
    void rejectsTrailingValuesCommentsAndGarbage(String suffix) {
        assertInvalid(valid() + suffix);
    }

    @Test
    void rejectsCommentsBeforeOrInsideTheDocument() {
        assertInvalid("/*private-secret*/" + valid());
        assertInvalid(valid().toString().replace("{", "{/*private-secret*/"));
    }

    @Test
    void rejectsDuplicateFieldsIncludingNestedAndEscapedFieldNames() {
        String json = valid().toString();
        assertInvalid(json.substring(0, json.length() - 1) + ",\"userId\":42}");
        assertInvalid(json.substring(0, json.length() - 1) + ",\"u\\u0073erId\":42}");
        assertInvalid(json.substring(0, json.length() - 1)
                + ",\"extra\":{\"private-secret\":1,\"private-secret\":2}}");
        assertInvalid(json.substring(0, json.length() - 1)
                + ",\"extra\":[{\"x\":1,\"x\":1}]}");
    }

    @Test
    void toleratesStrictlyValidUnknownFieldsAndKeepsBaseStringsUnmodified() {
        ObjectNode payload = valid().put("idempotencyKey", " private-key ")
                .put("externalReference", " private-destination ").put("memo", " private-memo ")
                .put("quorumProposalHash", " private-proposal ");
        payload.set("extra", JSON.createObjectNode().put("unknown", true));
        var binding = read(payload.toString()).binding();
        assertThat(binding.idempotencyKey()).isEqualTo(" private-key ");
        assertThat(binding.externalReference()).isEqualTo(" private-destination ");
        assertThat(binding.memo()).isEqualTo(" private-memo ");
        assertThat(binding.quorumProposalHash()).isEqualTo(" private-proposal ");
    }

    @ParameterizedTest(name = "invalid hint {0} = {1}")
    @MethodSource("invalidHints")
    void rejectsInvalidHintsInsteadOfIgnoringThem(String field, String rawValue) throws Exception {
        ObjectNode payload = valid();
        payload.set(field, JSON.readTree(rawValue));
        assertInvalid(payload.toString());
    }

    static Stream<Arguments> invalidHints() {
        return Stream.of("feeRateSatsPerVbyte", "feeRateSatPerVbyte", "feeTargetBlocks",
                        "confirmationTarget", "estimatedVbytes")
                .flatMap(field -> Stream.of("0", "-1", "1.0", "1e0", "true", "{}", "[]",
                                "9223372036854775808", "\"9223372036854775808\"", "\"\"", "\" \"",
                                "\"1.0\"", "\"1e2\"", "\"private-secret\"", "\"-1\"", "\"0\"",
                                "\"١\"")
                        .map(value -> Arguments.of(field, value)));
    }

    @Test
    void acceptsPositiveIntegerHintsAndTrimmedIntegerStringsAtTheirExactBounds() {
        var envelope = read(valid().put("feeRateSatsPerVbyte", Long.MAX_VALUE)
                .put("feeRateSatPerVbyte", " " + Long.MAX_VALUE + " ")
                .put("feeTargetBlocks", Integer.MAX_VALUE)
                .put("confirmationTarget", " " + Integer.MAX_VALUE + " ")
                .put("estimatedVbytes", " +0001 ").toString());
        assertThat(envelope.feeRateSatsPerVbyte()).isEqualTo(Long.MAX_VALUE);
        assertThat(envelope.feeTargetBlocks()).isEqualTo(Integer.MAX_VALUE);
        assertThat(envelope.estimatedVbytes()).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"feeTargetBlocks", "confirmationTarget"})
    void rejectsTargetValuesThatDoNotFitAnInteger(String field) {
        assertInvalid(valid().put(field, (long) Integer.MAX_VALUE + 1L).toString());
        assertInvalid(valid().put(field, Long.toString((long) Integer.MAX_VALUE + 1L)).toString());
    }

    @ParameterizedTest
    @MethodSource("hintAliases")
    void enforcesAliasAgreementAndDoesNotFallbackOnMalformedPrimaryOrAlias(String primary, String alias) {
        ObjectNode payload = valid().put(primary, 5).put(alias, " 5 ");
        read(payload.toString());
        assertInvalid(payload.put(alias, 6).toString());
        assertInvalid(payload.put(primary, "private-invalid").put(alias, 5).toString());
        assertInvalid(payload.put(primary, 5).put(alias, "private-invalid").toString());
        read(payload.putNull(primary).put(alias, 5).toString());
        read(payload.put(primary, 5).putNull(alias).toString());
        payload.remove(primary);
        read(payload.put(alias, 5).toString());
        payload.remove(alias);
        read(payload.toString());
    }

    static Stream<Arguments> hintAliases() {
        return Stream.of(Arguments.of("feeRateSatsPerVbyte", "feeRateSatPerVbyte"),
                Arguments.of("feeTargetBlocks", "confirmationTarget"));
    }

    @Test
    void treatsNullHintsAsAbsentAndTrimsQuoteIdWithoutExposingIt() {
        ObjectNode payload = valid().putNull("feeRateSatsPerVbyte").putNull("feeRateSatPerVbyte")
                .putNull("feeTargetBlocks").putNull("confirmationTarget").putNull("estimatedVbytes")
                .put("quoteId", " private-quote ");
        var envelope = read(payload.toString());
        assertThat(envelope.feeRateSatsPerVbyte()).isNull();
        assertThat(envelope.feeTargetBlocks()).isNull();
        assertThat(envelope.estimatedVbytes()).isNull();
        assertThat(envelope.quoteId()).isEqualTo("private-quote");
        assertThat(envelope.toString()).contains("REDACTED")
                .doesNotContain("private-quote", "private-destination", "private-memo",
                        "private-idempotency", "private-proposal");
        assertThat(read(payload.put("quoteId", " \t ").toString()).quoteId()).isNull();
    }

    @Test
    void canReadAgainAfterAnInvalidEnvelopeWithoutRetainingParserState() {
        assertInvalid("{\"private-secret\":");
        assertThat(read(valid().toString()).binding().transactionId()).isEqualTo(TRANSACTION);
    }

    private ExecutionEnvelopeReader.Envelope read(String json) {
        return reader.read(json, HASH.sha256(json));
    }

    private void assertInvalid(String json) {
        assertInvalid(json, HASH.sha256(json));
    }

    private void assertInvalid(String json, String hash) {
        var failure = assertThrows(ExecutionEnvelopeReader.InvalidExecutionEnvelope.class,
                () -> reader.read(json, hash));
        assertThat(failure).hasMessage("Execution envelope is invalid.").hasNoCause();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.toString()).doesNotContain("private-secret");
    }

    private static ObjectNode valid() {
        return JSON.createObjectNode().put("transactionId", TRANSACTION.toString()).put("userId", 42L)
                .put("idempotencyKey", "private-idempotency").put("rail", "ONCHAIN")
                .put("direction", "OUTBOUND").put("sourceWalletId", SOURCE.toString())
                .putNull("destinationWalletId").put("amountSats", 99_000L).put("networkFeeSats", 1_000L)
                .put("totalDebitSats", 101_000L).put("externalReference", "private-destination")
                .put("memo", "private-memo").put("quorumProposalHash", "private-proposal");
    }
}
