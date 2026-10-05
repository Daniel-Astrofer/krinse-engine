package com.kerosene.kfe.adapters.out.persistence.repository.audit;

/** Minimal projection used to page through ordered audit-chain hashes without loading full entities. */
public interface KfeAuditHashRow {

    /** @return sequence position assigned to this audit event by the database */
    Long getSequenceNumber();

    /** @return hash of the audit event at the projected sequence position */
    String getEventHash();
}
