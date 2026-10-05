package com.kerosene.kfe.audit.adapters.in.reporting;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.adapters.in.http.dto.ledger.KfeClassifyTaxEventRequest;
import com.kerosene.kfe.adapters.in.http.dto.ledger.KfeTaxEventResponse;
import com.kerosene.kfe.adapters.in.http.dto.ledger.KfeTaxEventsExportResponse;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeTaxEventClassificationEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeTaxEventClassificationRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Produces user-scoped tax-oriented event views from KFE transactions and saved classifications.
 * The output is an operational aid with an explicit notice, not a tax determination.
 */
@Service
public class KfeTaxEventService {

    /** User-facing notice included with every export to clarify the data's advisory role. */
    private static final String EDUCATIONAL_NOTICE =
            "Eventos derivados do KFE para apoio operacional. Não substituem orientação fiscal profissional.";

    /** User-scoped transaction source for deriving deposit, withdrawal, and transfer events. */
    private final KfeTransactionRepository transactionRepository;
    /** Repository for per-user event classifications selected by the user. */
    private final KfeTaxEventClassificationRepository classificationRepository;

    /**
     * Creates the tax event service with transaction and classification persistence.
     *
     * @param transactionRepository source of user-owned transactions
     * @param classificationRepository source of saved user event classifications
     */
    public KfeTaxEventService(
            KfeTransactionRepository transactionRepository,
            KfeTaxEventClassificationRepository classificationRepository) {
        this.transactionRepository = transactionRepository;
        this.classificationRepository = classificationRepository;
    }

    /**
     * Lists up to 200 newest user transactions mapped to tax-event response records.
     * Saved classifications override direction-based defaults.
     *
     * @param userId user whose transactions are listed
     * @return newest-first derived events
     */
    @Transactional(readOnly = true)
    public List<KfeTaxEventResponse> list(Long userId) {
        Map<String, KfeTaxEventClassificationEntity> classifications = classifications(userId);
        return transactionRepository.findTop200ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(transaction -> toTaxEvent(transaction, classifications))
                .toList();
    }

    /**
     * Exports the current derived events as CSV or a compact JSON array and includes the advisory notice.
     * Any format other than CSV is normalized to JSON.
     *
     * @param userId user whose events are exported
     * @param format requested format, case-insensitive
     * @return export metadata, content, and the source event list
     */
    @Transactional(readOnly = true)
    public KfeTaxEventsExportResponse export(Long userId, String format) {
        String normalizedFormat = normalizeFormat(format);
        List<KfeTaxEventResponse> events = list(userId);
        String filename = "kerosene-kfe-tax-events." + normalizedFormat;
        String content = "csv".equals(normalizedFormat) ? csv(events) : jsonLike(events);
        return new KfeTaxEventsExportResponse(
                normalizedFormat,
                filename,
                EDUCATIONAL_NOTICE,
                content,
                events);
    }

    /**
     * Saves or replaces one user's classification for a transaction-backed event.
     * Event IDs may contain a suffix after a colon; only the UUID prefix identifies the transaction.
     *
     * @param userId owner of both the transaction and its classification
     * @param eventId event identifier, usually a transaction UUID
     * @param request requested user classification
     * @return the event view using the newly persisted classification
     * @throws IllegalArgumentException when identifiers/classification are missing or transaction is absent
     */
    @Transactional
    public KfeTaxEventResponse classify(Long userId, String eventId, KfeClassifyTaxEventRequest request) {
        String cleanEventId = requireText(eventId, "eventId");
        String classification = requireText(request != null ? request.classification() : null, "classification")
                .toUpperCase(Locale.ROOT);
        KfeTransactionEntity transaction = transactionRepository.findByIdAndUserId(resolveTransactionId(cleanEventId), userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE tax event not found."));
        KfeTaxEventClassificationEntity entity = classificationRepository
                .findByUserIdAndEventId(userId, cleanEventId)
                .orElseGet(KfeTaxEventClassificationEntity::new);
        entity.setUserId(userId);
        entity.setEventId(cleanEventId);
        entity.setClassification(classification);
        classificationRepository.save(entity);
        return toTaxEvent(transaction, Map.of(cleanEventId, entity));
    }

    /**
     * Combines transaction data, user override/default classification, wallet IDs, and source reference.
     *
     * @param transaction persisted transaction visible to the requesting user
     * @param classifications user's classifications keyed by event ID
     * @return normalized tax event response with a one-year review date
     */
    private KfeTaxEventResponse toTaxEvent(
            KfeTransactionEntity transaction,
            Map<String, KfeTaxEventClassificationEntity> classifications) {
        String eventId = transaction.getId().toString();
        String eventType = eventType(transaction);
        String classification = classifications.containsKey(eventId)
                ? classifications.get(eventId).getClassification()
                : defaultClassification(eventType);
        UUID walletId = transaction.getSourceWalletId() != null
                ? transaction.getSourceWalletId()
                : transaction.getDestinationWalletId();
        return new KfeTaxEventResponse(
                eventId,
                eventType,
                "BTC",
                quantity(transaction),
                classification,
                sourceRef(transaction),
                transaction.getCreatedAt(),
                walletId,
                transaction.getSourceWalletId(),
                walletId,
                transaction.getCreatedAt() != null ? transaction.getCreatedAt().plusDays(365) : null);
    }

    /**
     * Loads all saved classifications for a user into an insertion-ordered map keyed by event ID.
     * Duplicate keys, if returned by persistence, retain the last row.
     *
     * @param userId owner whose classifications are loaded
     * @return classifications keyed by event identifier
     */
    private Map<String, KfeTaxEventClassificationEntity> classifications(Long userId) {
        return classificationRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(
                        KfeTaxEventClassificationEntity::getEventId,
                        Function.identity(),
                        (left, right) -> right,
                        LinkedHashMap::new));
    }

    /**
     * Derives an event category from transaction direction and whether outbound fees were charged.
     *
     * @param transaction source transaction
     * @return normalized event category such as DEPOSIT_EXTERNAL, WITHDRAWAL, or SELF_TRANSFER
     */
    private String eventType(KfeTransactionEntity transaction) {
        if (transaction.getNetworkFeeSats() > 0 || transaction.getKeroseneFeeSats() > 0) {
            if (transaction.getDirection() == KfeDirection.OUTBOUND) {
                return "WITHDRAWAL";
            }
        }
        if (transaction.getDirection() == KfeDirection.INBOUND) {
            return "DEPOSIT_EXTERNAL";
        }
        if (transaction.getDirection() == KfeDirection.OUTBOUND) {
            return "WITHDRAWAL";
        }
        if (transaction.getDirection() == KfeDirection.INTERNAL) {
            return "SELF_TRANSFER";
        }
        return "KFE_TRANSACTION";
    }

    /**
     * Supplies a direction-based starting classification when the user has not set an override.
     *
     * @param eventType derived transaction event type
     * @return default classification label or UNCLASSIFIED
     */
    private String defaultClassification(String eventType) {
        return switch (eventType) {
            case "DEPOSIT_EXTERNAL" -> "INCOME_OR_TRANSFER_IN";
            case "WITHDRAWAL" -> "TRANSFER_OUT_OR_SPEND";
            case "SELF_TRANSFER" -> "SELF_TRANSFER";
            default -> "UNCLASSIFIED";
        };
    }

    /**
     * Chooses received amount when positive, otherwise gross amount, and clamps below zero.
     *
     * @param transaction source transaction
     * @return derived event quantity in satoshis
     */
    private long quantity(KfeTransactionEntity transaction) {
        long value = transaction.getReceiverAmountSats() > 0
                ? transaction.getReceiverAmountSats()
                : transaction.getGrossAmountSats();
        return Math.max(0L, value);
    }

    /**
     * Selects the strongest available external reference for reconciliation and export.
     * Preference is blockchain transaction ID, payment hash, provider reference, then internal ID.
     *
     * @param transaction source transaction
     * @return external reference or transaction UUID string
     */
    private String sourceRef(KfeTransactionEntity transaction) {
        if (hasText(transaction.getBlockchainTxid())) {
            return transaction.getBlockchainTxid();
        }
        if (hasText(transaction.getPaymentHash())) {
            return transaction.getPaymentHash();
        }
        if (hasText(transaction.getProviderReference())) {
            return transaction.getProviderReference();
        }
        return transaction.getId().toString();
    }

    /**
     * Parses the transaction UUID prefix of an event identifier.
     *
     * @param eventId event text, optionally suffixed after a colon
     * @return parsed transaction UUID
     * @throws IllegalArgumentException when the prefix is not a valid UUID
     */
    private UUID resolveTransactionId(String eventId) {
        String normalized = eventId;
        int separator = normalized.indexOf(':');
        if (separator > 0) {
            normalized = normalized.substring(0, separator);
        }
        return UUID.fromString(normalized);
    }

    /**
     * Normalizes export selection to the supported CSV/JSON pair, defaulting to JSON.
     *
     * @param format requested export format
     * @return {@code csv} only for CSV input, otherwise {@code json}
     */
    private String normalizeFormat(String format) {
        String normalized = hasText(format) ? format.trim().toLowerCase(Locale.ROOT) : "json";
        return "csv".equals(normalized) ? "csv" : "json";
    }

    /**
     * Serializes the supported event columns as escaped CSV with a stable header.
     *
     * @param events events to serialize in their existing order
     * @return UTF-8-ready CSV text (encoding is applied by the HTTP layer)
     */
    private String csv(List<KfeTaxEventResponse> events) {
        StringBuilder builder = new StringBuilder("id,eventType,asset,quantitySats,classification,sourceRef,createdAt,walletId\n");
        for (KfeTaxEventResponse event : events) {
            builder.append(csvCell(event.id())).append(',')
                    .append(csvCell(event.eventType())).append(',')
                    .append(csvCell(event.asset())).append(',')
                    .append(event.quantitySats()).append(',')
                    .append(csvCell(event.classification())).append(',')
                    .append(csvCell(event.sourceRef())).append(',')
                    .append(csvCell(String.valueOf(event.createdAt()))).append(',')
                    .append(csvCell(String.valueOf(event.walletId())))
                    .append('\n');
        }
        return builder.toString();
    }

    /**
     * Serializes a compact JSON-like array containing event ID, type, and satoshi quantity.
     *
     * @param events events to serialize
     * @return JSON array text for the compact export representation
     */
    private String jsonLike(List<KfeTaxEventResponse> events) {
        return events.stream()
                .map(event -> "{\"id\":\"" + event.id() + "\",\"eventType\":\"" + event.eventType()
                        + "\",\"quantitySats\":" + event.quantitySats() + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * Escapes a CSV field by quoting it and doubling embedded quote characters.
     *
     * @param value nullable field value
     * @return valid quoted CSV cell, with null represented as empty text
     */
    private String csvCell(String value) {
        String clean = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + clean + "\"";
    }

    /**
     * Trims a required user-supplied string and names the missing field in validation errors.
     *
     * @param value submitted field value
     * @param field field name for the error message
     * @return trimmed nonblank value
     * @throws IllegalArgumentException when value is absent or blank
     */
    private String requireText(String value, String field) {
        if (!hasText(value)) {
            throw new IllegalArgumentException("KFE tax event " + field + " is required.");
        }
        return value.trim();
    }

    /**
     * Checks whether a nullable value contains non-whitespace text.
     *
     * @param value candidate string
     * @return true when nonnull and nonblank
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
