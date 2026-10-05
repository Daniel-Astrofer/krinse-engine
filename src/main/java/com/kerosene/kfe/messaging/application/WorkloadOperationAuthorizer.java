package com.kerosene.kfe.messaging.application;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit allow-list for workload-to-operation authorization.
 *
 * <p>An authenticated SPIFFE identity alone is not permission to publish every
 * message. Missing or unknown policy is deliberately denied.</p>
 */
public final class WorkloadOperationAuthorizer {

    /** Immutable normalized allow-list keyed by authenticated SPIFFE identity. */
    private final Map<String, Set<String>> allowedOperationsByWorkload;

    /**
     * Creates a fail-closed authorization policy and defensively normalizes/copies every identity
     * and operation. A workload with a null operation set is retained with no permissions.
     *
     * @param allowedOperationsByWorkload configured mapping from SPIFFE ID to permitted operation names
     * @throws IllegalArgumentException if a workload key or operation name is blank
     */
    public WorkloadOperationAuthorizer(Map<String, Set<String>> allowedOperationsByWorkload) {
        Objects.requireNonNull(allowedOperationsByWorkload, "allowedOperationsByWorkload is required");
        this.allowedOperationsByWorkload = allowedOperationsByWorkload.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> normalize(entry.getKey(), "workload"),
                        entry -> entry.getValue() == null
                                ? Set.of()
                                : entry.getValue().stream()
                                        .map(value -> normalize(value, "operation"))
                                        .collect(java.util.stream.Collectors.toUnmodifiableSet())));
    }

    /**
     * Requires an authenticated workload to be explicitly allowed to perform the named operation.
     * Unknown identities and unlisted operations are rejected instead of falling back to a default.
     *
     * @param workload transport-authenticated identity; message content cannot choose this value
     * @param operation normalized application operation requested by the caller
     * @throws SecurityException if the allow-list does not grant this operation
     */
    public void requireAllowed(WorkloadIdentity workload, String operation) {
        Objects.requireNonNull(workload, "workload is required");
        String normalizedOperation = normalize(operation, "operation");
        Set<String> allowed = allowedOperationsByWorkload.get(workload.spiffeId());
        if (allowed == null || !allowed.contains(normalizedOperation)) {
            throw new SecurityException("workload is not authorized for operation: " + normalizedOperation);
        }
    }

    /** Trims a policy key and rejects null or whitespace-only configuration values. */
    private static String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
