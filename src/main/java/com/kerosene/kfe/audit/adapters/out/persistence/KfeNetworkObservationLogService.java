package com.kerosene.kfe.audit.adapters.out.persistence;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.adapters.out.persistence.model.audit.KfeNetworkObservationLogEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.audit.KfeNetworkObservationLogRepository;

import java.util.Locale;
import java.util.UUID;

/** Stores observation metadata only; provider payloads and secrets never enter the log. */
@Service
public class KfeNetworkObservationLogService {

    /** Persistence port for sanitized chain-observation history. */
    private final KfeNetworkObservationLogRepository repository;

    /**
     * Creates the observation logger with its append-only persistence repository.
     *
     * @param repository storage for normalized transaction observations
     */
    public KfeNetworkObservationLogService(KfeNetworkObservationLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Persists one normalized confirmation observation; incomplete transaction/reference pairs are ignored.
     * The stored reference is lowercased and capped at 64 characters, and confirmations cannot be negative.
     *
     * @param transactionId KFE transaction associated with the observation
     * @param networkReference provider transaction reference or txid
     * @param state provider observation state
     * @param confirmations observed confirmation count
     */
    @Transactional
    public void record(
            UUID transactionId,
            String networkReference,
            String state,
            int confirmations) {
        if (transactionId == null || networkReference == null || networkReference.isBlank()) {
            return;
        }
        KfeNetworkObservationLogEntity entity = new KfeNetworkObservationLogEntity();
        entity.setTransactionId(transactionId);
        String normalizedReference = networkReference.trim().toLowerCase(Locale.ROOT);
        entity.setTxid(normalizedReference.substring(0, Math.min(64, normalizedReference.length())));
        entity.setState(normalizeState(state));
        entity.setConfirmations(Math.max(0, confirmations));
        repository.save(entity);
    }

    /**
     * Normalizes a provider state into an uppercase bounded label.
     *
     * @param state raw provider state
     * @return UNKNOWN when absent, otherwise trimmed uppercase text capped at 32 characters
     */
    private String normalizeState(String state) {
        if (state == null || state.isBlank()) {
            return "UNKNOWN";
        }
        return state.trim().toUpperCase(Locale.ROOT).substring(0, Math.min(32, state.trim().length()));
    }
}
