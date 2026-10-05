package com.kerosene.kfe.paymentexecution.adapters.out.execution;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.paymentexecution.domain.exception.KfeExecutionClaimLostException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.security.CryptoPurpose;
import com.kerosene.common.security.EncryptedValue;
import com.kerosene.common.security.StringColumnCryptoPort;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/** Persists the exact externally executable payload before the first provider call. */
@Service
public class KfePreparedExecutionService {

    /** Version embedded in the encrypted envelope so future payload layouts can be rejected or migrated. */
    private static final int SCHEMA_VERSION = 1;
    /** Upper bound for serialized encrypted execution content to prevent oversized persisted payloads. */
    private static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;

    /** Locks and persists execution outbox ownership and prepared payload columns. */
    private final KfeExecutionOutboxRepository outboxRepository;
    /** Encrypts/decrypts payload bytes using authenticated column encryption. */
    private final StringColumnCryptoPort cryptoPort;
    /** Hashes plaintext payload bytes for tamper detection after decryption. */
    private final KfeHashService hashService;
    /** Serializes envelope, ciphertext wrapper, and typed payload content. */
    private final ObjectMapper objectMapper;

    /**
     * Creates the encrypted prepared-execution persistence service.
     *
     * @param outboxRepository execution outbox repository with row-lock support
     * @param cryptoPort authenticated column encryption port
     * @param hashService SHA-256 hashing service
     * @param objectMapper JSON envelope and payload mapper
     */
    public KfePreparedExecutionService(
            KfeExecutionOutboxRepository outboxRepository,
            StringColumnCryptoPort cryptoPort,
            KfeHashService hashService,
            ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.cryptoPort = cryptoPort;
        this.hashService = hashService;
        this.objectMapper = objectMapper;
    }

    /** Identifies the external payment rail whose immutable request body is stored. */
    public enum PayloadType {
        /** Prepared on-chain transaction submission request. */
        ONCHAIN,
        /** Prepared Lightning payment submission request. */
        LIGHTNING
    }

    /** Typed decrypted payload paired with the stable provider-side execution reference. */
    /**
     * Typed decrypted payload paired with the stable provider-side execution reference.
     *
     * @param payload exact typed request previously prepared for the external provider
     * @param executionReference stable idempotency/reference value used for provider execution
     * @param <T> provider payload type
     */
    public record StoredPayload<T>(T payload, String executionReference) {
    }

    /** Authenticated context envelope encrypted with the provider-ready payload. */
    /**
     * Authenticated context envelope encrypted with the provider-ready payload.
     *
     * @param schemaVersion envelope format version
     * @param outboxId execution outbox identifier
     * @param transactionId financial transaction identifier
     * @param operation outbox operation identity
     * @param payloadType on-chain or Lightning payload family
     * @param payload serialized provider request body
     */
    private record Envelope(
            int schemaVersion,
            UUID outboxId,
            UUID transactionId,
            String operation,
            PayloadType payloadType,
            JsonNode payload) {
    }

    /**
     * Loads a previously prepared provider payload only while the caller owns a live execution lease.
     * Returns empty only when all prepared fields are absent; partially populated persisted state is
     * treated as corruption instead of silently rebuilding a potentially different provider request.
     *
     * @param outboxId execution outbox identifier
     * @param transactionId associated financial transaction identifier
     * @param claimToken current worker lease token
     * @param operation expected outbox operation
     * @param payloadType expected provider payload family
     * @param payloadClass Java type to materialize from the encrypted JSON payload
     * @param <T> provider payload type
     * @return stored payload/reference when already prepared, otherwise empty
     */
    @Transactional
    public <T> Optional<StoredPayload<T>> load(
            UUID outboxId,
            UUID transactionId,
            UUID claimToken,
            String operation,
            PayloadType payloadType,
            Class<T> payloadClass) {
        KfeExecutionOutboxEntity outbox = lockedOwnedOutbox(
                outboxId, transactionId, claimToken, operation);
        if (outbox.getPreparedPayloadCiphertext() == null) {
            if (outbox.getPreparedPayloadHash() != null || outbox.getExecutionReference() != null) {
                throw new IllegalStateException("Prepared execution fields are only partially populated.");
            }
            return Optional.empty();
        }
        return Optional.of(decrypt(outbox, payloadType, payloadClass));
    }

    /**
     * Persists the exact provider-ready payload once under the current live claim, returning the
     * existing value on retries. Envelope identity, payload type, operation, and transaction are
     * authenticated as encryption associated data and checked again after decryption.
     *
     * @param outboxId execution outbox identifier
     * @param transactionId associated financial transaction identifier
     * @param claimToken current worker lease token
     * @param operation expected outbox operation
     * @param payloadType provider payload family
     * @param payload exact typed provider request to preserve across retries
     * @param executionReference stable provider reference, 1 to 255 trimmed characters
     * @param payloadClass expected Java payload type for existing-payload recovery
     * @param <T> provider payload type
     * @return persisted or previously stored payload and stable execution reference
     * @throws IllegalArgumentException for missing payload or invalid reference/size
     * @throws KfeExecutionClaimLostException when the lease is absent, expired, or owned by another worker
     */
    @Transactional
    public <T> StoredPayload<T> persistIfAbsent(
            UUID outboxId,
            UUID transactionId,
            UUID claimToken,
            String operation,
            PayloadType payloadType,
            T payload,
            String executionReference,
            Class<T> payloadClass) {
        if (payload == null) {
            throw new IllegalArgumentException("Prepared execution payload is required.");
        }
        String normalizedReference = normalizeReference(executionReference);
        KfeExecutionOutboxEntity outbox = lockedOwnedOutbox(
                outboxId, transactionId, claimToken, operation);
        if (outbox.getPreparedPayloadCiphertext() != null) {
            return decrypt(outbox, payloadType, payloadClass);
        }

        try {
            Envelope envelope = new Envelope(
                    SCHEMA_VERSION,
                    outboxId,
                    transactionId,
                    operation,
                    payloadType,
                    objectMapper.valueToTree(payload));
            byte[] plaintext = objectMapper.writeValueAsBytes(envelope);
            if (plaintext.length == 0 || plaintext.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Prepared execution payload size is invalid.");
            }
            EncryptedValue encrypted = cryptoPort.encrypt(
                    CryptoPurpose.COLUMN_ENCRYPTION,
                    plaintext,
                    associatedData(outbox));
            outbox.setPreparedPayloadCiphertext(objectMapper.writeValueAsString(encrypted));
            outbox.setPreparedPayloadHash(hashService.sha256(new String(plaintext, StandardCharsets.UTF_8)));
            outbox.setExecutionReference(normalizedReference);
            outboxRepository.saveAndFlush(outbox);
            return new StoredPayload<>(payload, normalizedReference);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not persist prepared external execution.", exception);
        }
    }

    /**
     * Decrypts, authenticates, hashes, validates, and materializes the prepared payload envelope.
     *
     * @param outbox locked execution outbox entity
     * @param expectedType provider payload family required by the caller
     * @param payloadClass target Java payload type
     * @param <T> provider payload type
     * @return decrypted payload and persisted execution reference
     * @throws IllegalStateException for partial, oversized, tampered, or mismatched envelope data
     */
    private <T> StoredPayload<T> decrypt(
            KfeExecutionOutboxEntity outbox,
            PayloadType expectedType,
            Class<T> payloadClass) {
        try {
            if (outbox.getPreparedPayloadHash() == null || outbox.getExecutionReference() == null) {
                throw new IllegalStateException("Prepared execution fields are only partially populated.");
            }
            EncryptedValue encrypted = objectMapper.readValue(
                    outbox.getPreparedPayloadCiphertext(), EncryptedValue.class);
            byte[] plaintext = cryptoPort.decrypt(
                    CryptoPurpose.COLUMN_ENCRYPTION,
                    encrypted,
                    associatedData(outbox));
            if (plaintext.length == 0 || plaintext.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalStateException("Prepared execution plaintext size is invalid.");
            }
            String actualHash = hashService.sha256(new String(plaintext, StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(
                    actualHash.getBytes(StandardCharsets.US_ASCII),
                    outbox.getPreparedPayloadHash().getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalStateException("Prepared execution plaintext hash mismatch.");
            }
            Envelope envelope = objectMapper.readValue(plaintext, Envelope.class);
            if (envelope.schemaVersion() != SCHEMA_VERSION
                    || !outbox.getId().equals(envelope.outboxId())
                    || !outbox.getTransactionId().equals(envelope.transactionId())
                    || !outbox.getOperation().equals(envelope.operation())
                    || expectedType != envelope.payloadType()) {
                throw new IllegalStateException("Prepared execution envelope context mismatch.");
            }
            T payload = objectMapper.treeToValue(envelope.payload(), payloadClass);
            return new StoredPayload<>(payload, outbox.getExecutionReference());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not decrypt prepared external execution.", exception);
        }
    }

    /**
     * Locks the outbox row and proves transaction, operation, PROCESSING status, unexpired lease,
     * and claim-token ownership before any payload read or write.
     *
     * @param outboxId execution outbox identifier
     * @param transactionId expected financial transaction identifier
     * @param claimToken worker claim token
     * @param operation expected operation discriminator
     * @return locked row owned by the live claim
     * @throws IllegalArgumentException when required ownership inputs are missing
     * @throws IllegalStateException when the row is absent or bound to another operation
     * @throws KfeExecutionClaimLostException when claim status/token/lease is no longer valid
     */
    private KfeExecutionOutboxEntity lockedOwnedOutbox(
            UUID outboxId,
            UUID transactionId,
            UUID claimToken,
            String operation) {
        if (outboxId == null || transactionId == null || claimToken == null
                || operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("Outbox execution ownership context is required.");
        }
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Execution outbox entry was not found."));
        if (!transactionId.equals(outbox.getTransactionId()) || !operation.equals(outbox.getOperation())) {
            throw new IllegalStateException("Execution outbox context does not match the prepared operation.");
        }
        if (!"PROCESSING".equals(outbox.getStatus())
                || !claimToken.equals(outbox.getClaimToken())
                || outbox.getLeaseExpiresAt() == null
                || !outbox.getLeaseExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new KfeExecutionClaimLostException(outboxId);
        }
        return outbox;
    }

    /**
     * Builds stable authenticated-encryption context binding ciphertext to table, column, row,
     * transaction, operation, and envelope version.
     *
     * @param outbox execution outbox row providing the identity components
     * @return UTF-8 associated-data bytes passed to the crypto provider
     */
    private byte[] associatedData(KfeExecutionOutboxEntity outbox) {
        return String.join("|",
                        "financial.financial_execution_outbox",
                        "prepared_payload_ciphertext",
                        outbox.getId().toString(),
                        outbox.getTransactionId().toString(),
                        outbox.getOperation(),
                        "v1")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Trims and validates a nonempty provider execution reference within the database column limit.
     *
     * @param executionReference caller-provided stable provider reference
     * @return trimmed reference
     * @throws IllegalArgumentException when reference length is outside 1..255 characters
     */
    private String normalizeReference(String executionReference) {
        String value = executionReference == null ? "" : executionReference.trim();
        if (value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException("Prepared execution reference must be 1 to 255 characters.");
        }
        return value;
    }
}
