package com.kerosene.kfe.wallet.domain.service;

import com.kerosene.kfe.wallet.domain.model.Outpoint;
import com.kerosene.kfe.wallet.domain.model.UtxoSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic merge of chain observations by immutable outpoint. */
public final class UtxoProjectionPolicy {
    /** Prevents instantiation of the stateless projection policy. */
    private UtxoProjectionPolicy() {
    }

    /**
     * Merges an observation by outpoint, retaining the observation with more confirmations.
     * Null projection or candidate is ignored so partial adapter results cannot break aggregation.
     */
    public static void merge(Map<Outpoint, UtxoSnapshot> projection, UtxoSnapshot candidate) {
        if (projection == null || candidate == null) {
            return;
        }
        projection.merge(candidate.outpoint(), candidate, UtxoProjectionPolicy::preferFreshest);
    }

    /** Creates an insertion-ordered mutable projection for deterministic aggregation. */
    public static Map<Outpoint, UtxoSnapshot> newProjection() {
        return new LinkedHashMap<>();
    }

    /** Keeps the prior observation on ties and replaces it only with a fresher confirmation count. */
    private static UtxoSnapshot preferFreshest(UtxoSnapshot previous, UtxoSnapshot candidate) {
        return candidate.confirmations() > previous.confirmations() ? candidate : previous;
    }
}
