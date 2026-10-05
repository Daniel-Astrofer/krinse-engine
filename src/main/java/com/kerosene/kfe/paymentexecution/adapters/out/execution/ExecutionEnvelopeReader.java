package com.kerosene.kfe.paymentexecution.adapters.out.execution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionIntentBinding;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Reads the persisted wire envelope without trusting Jackson coercions or the shared mapper. */
public final class ExecutionEnvelopeReader {

    /** Isolated strict JSON mapper; duplicate object keys are rejected instead of silently overwritten. */
    private final ObjectMapper mapper;

    /**
     * Creates a parser dedicated to the durable execution wire format with strict duplicate-key detection.
     */
    public ExecutionEnvelopeReader() {
        mapper = new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
    }

    /**
     * Verifies the exact UTF-8 JSON hash, parses one strict JSON object, and validates all required
     * execution identity/economic fields before constructing a typed envelope. Numeric fee options
     * accept documented legacy aliases only when both names agree.
     *
     * @param json persisted execution envelope bytes represented as text
     * @param expectedSha256 stored SHA-256 hex digest of the exact JSON text
     * @return validated immutable execution envelope
     * @throws InvalidExecutionEnvelope when hash, JSON shape, field type, range, or enum value is invalid
     * @throws IllegalStateException only when the platform lacks its required SHA-256 implementation
     */
    public Envelope read(String json, String expectedSha256) {
        verifyHash(json, expectedSha256);
        JsonNode root;
        try (JsonParser parser = mapper.createParser(json)) {
            root = mapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw invalid();
            }
        } catch (IOException exception) {
            // Parser diagnostics can include the financial payload. Do not retain their cause.
            throw invalid();
        }

        ExecutionIntentBinding binding = new ExecutionIntentBinding(
                uuid(requiredText(root, "transactionId")),
                requiredLong(root, "userId"),
                requiredText(root, "idempotencyKey"),
                rail(requiredText(root, "rail")),
                direction(requiredText(root, "direction")),
                optionalUuid(root, "sourceWalletId"),
                optionalUuid(root, "destinationWalletId"),
                requiredLong(root, "amountSats"),
                requiredLong(root, "networkFeeSats"),
                requiredLong(root, "totalDebitSats"),
                optionalText(root, "externalReference"),
                optionalText(root, "memo"),
                requiredText(root, "quorumProposalHash"));

        Long rate = aliasedPositiveLong(root, "feeRateSatsPerVbyte", "feeRateSatPerVbyte");
        Long target = aliasedPositiveLong(root, "feeTargetBlocks", "confirmationTarget");
        if (target != null && target > Integer.MAX_VALUE) {
            throw invalid();
        }
        String quoteId = optionalText(root, "quoteId");
        quoteId = quoteId == null || quoteId.isBlank() ? null : quoteId.trim();
        return new Envelope(binding, rate, target == null ? null : target.intValue(),
                optionalPositiveLong(root, "estimatedVbytes"), quoteId);
    }

    /**
     * Compares the exact JSON UTF-8 digest to a 64-character hexadecimal expected hash in constant time.
     *
     * @param json raw persisted envelope text
     * @param expectedSha256 expected digest, case-insensitive hexadecimal
     * @throws InvalidExecutionEnvelope for absent/malformed/mismatched digest input
     */
    private static void verifyHash(String json, String expectedSha256) {
        if (json == null || expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}")) {
            throw invalid();
        }
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(actual, HexFormat.of().parseHex(expectedSha256))) {
                throw invalid();
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    /**
     * Reads a required textual property without coercing numbers, booleans, or nulls.
     *
     * @param root envelope JSON object
     * @param name required property name
     * @return exact textual value
     * @throws InvalidExecutionEnvelope when absent or nontextual
     */
    private static String requiredText(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    /**
     * Reads an optional textual property; explicit JSON null is treated as absent.
     *
     * @param root envelope JSON object
     * @param name optional property name
     * @return text value or null when absent/null
     * @throws InvalidExecutionEnvelope when present with a nontextual value
     */
    private static String optionalText(JsonNode root, String name) {
        JsonNode value = root.get(name);
        return value == null || value.isNull() ? null : requiredText(root, name);
    }

    /**
     * Reads an integral JSON number that fits in a signed 64-bit long; string coercion is not allowed.
     *
     * @param root envelope JSON object
     * @param name required property name
     * @return exact integer value
     * @throws InvalidExecutionEnvelope when absent, fractional, or out of range
     */
    private static long requiredLong(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid();
        }
        return value.longValue();
    }

    /**
     * Reads an optional UUID text field while requiring canonical UUID formatting.
     *
     * @param root envelope JSON object
     * @param name optional UUID property
     * @return parsed UUID or null when missing/JSON null
     * @throws InvalidExecutionEnvelope when present but malformed
     */
    private static UUID optionalUuid(JsonNode root, String name) {
        String value = optionalText(root, name);
        return value == null ? null : uuid(value);
    }

    /**
     * Parses a UUID and rejects noncanonical forms that UUID.fromString might otherwise normalize.
     *
     * @param value UUID text
     * @return parsed UUID
     * @throws InvalidExecutionEnvelope for malformed/noncanonical text
     */
    private static UUID uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) {
                throw invalid();
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /**
     * Resolves a case-sensitive serialized payment rail enum value.
     *
     * @param value persisted rail name
     * @return typed rail
     * @throws InvalidExecutionEnvelope when the name is unknown
     */
    private static PaymentRail rail(String value) {
        try {
            return PaymentRail.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /**
     * Resolves a case-sensitive serialized payment direction enum value.
     *
     * @param value persisted direction name
     * @return typed direction
     * @throws InvalidExecutionEnvelope when the name is unknown
     */
    private static PaymentDirection direction(String value) {
        try {
            return PaymentDirection.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /**
     * Reads a positive integer from a primary/legacy alias pair and rejects conflicting dual values.
     *
     * @param root envelope JSON object
     * @param name preferred property name
     * @param alias compatibility property name
     * @return positive value from either name, or null when both are absent/null
     * @throws InvalidExecutionEnvelope for invalid or conflicting values
     */
    private static Long aliasedPositiveLong(JsonNode root, String name, String alias) {
        Long primary = optionalPositiveLong(root, name);
        Long alternate = optionalPositiveLong(root, alias);
        if (primary != null && alternate != null && !Objects.equals(primary, alternate)) {
            throw invalid();
        }
        return primary == null ? alternate : primary;
    }

    /**
     * Reads a positive integer property encoded as an integral JSON value or signed decimal text.
     * Zero, negative, fractional, boolean, and out-of-range values are invalid.
     *
     * @param root envelope JSON object
     * @param name property name
     * @return positive value or null when absent/JSON null
     * @throws InvalidExecutionEnvelope when present but invalid
     */
    private static Long optionalPositiveLong(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        long number;
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            number = value.longValue();
        } else if (value.isTextual()) {
            String text = value.textValue().trim();
            if (!text.matches("[+-]?[0-9]+")) {
                throw invalid();
            }
            try {
                number = Long.parseLong(text);
            } catch (NumberFormatException exception) {
                throw invalid();
            }
        } else {
            throw invalid();
        }
        if (number <= 0L) {
            throw invalid();
        }
        return number;
    }

    /** @return payload-safe exception with a fixed message for all malformed-envelope cases */
    private static InvalidExecutionEnvelope invalid() {
        return new InvalidExecutionEnvelope();
    }

    /**
     * Typed and validated view of the execution intent plus optional fee-quote parameters.
     *
     * @param binding required payment, user, wallet, amount, and quorum identity
     * @param feeRateSatsPerVbyte positive fee rate in sat/vbyte, if supplied
     * @param feeTargetBlocks positive confirmation target, if supplied
     * @param estimatedVbytes positive size estimate, if supplied
     * @param quoteId optional trimmed fee quote identifier
     */
    public record Envelope(ExecutionIntentBinding binding, Long feeRateSatsPerVbyte,
                           Integer feeTargetBlocks, Long estimatedVbytes, String quoteId) {
        /**
         * Redacts optional reference fields from diagnostic string output.
         *
         * @return envelope summary with references explicitly redacted
         */
        @Override
        public String toString() {
            return "Envelope[binding=" + binding + ", references=REDACTED]";
        }
    }

    /** Fixed-message validation exception that never retains parser diagnostics containing payload data. */
    public static final class InvalidExecutionEnvelope extends IllegalArgumentException {
        /** Creates a payload-safe malformed-envelope exception. */
        public InvalidExecutionEnvelope() {
            super("Execution envelope is invalid.");
        }
    }
}
