package com.kerosene.kfe.adapters.out.persistence.model.liquidity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Immutable evidence of a propose-only planning cycle, never an execution authorization. */
@Entity
@Table(name = "treasury_proposals", schema = "financial")
public class KfeTreasuryProposalEntity {
    @Id private UUID id;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    @Column(name="valid_until", nullable=false, updatable=false) private Instant validUntil;
    @Column(name="policy_version", nullable=false, updatable=false, length=128) private String policyVersion;
    @Column(name="evidence_hash", nullable=false, updatable=false, length=64) private String evidenceHash;
    @Column(name="evidence_json", nullable=false, updatable=false, columnDefinition="TEXT") private String evidenceJson;
    @Column(name="plan_json", nullable=false, updatable=false, columnDefinition="TEXT") private String planJson;
    protected KfeTreasuryProposalEntity() { }
    public KfeTreasuryProposalEntity(Instant at, Instant until, String version, String hash, String evidence, String plan) {
        id=UUID.randomUUID(); createdAt=at; validUntil=until; policyVersion=version;
        evidenceHash=hash; evidenceJson=evidence; planJson=plan;
    }
    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getValidUntil() { return validUntil; }
    public String getPolicyVersion() { return policyVersion; }
    public String getEvidenceHash() { return evidenceHash; }
    public String getEvidenceJson() { return evidenceJson; }
    public String getPlanJson() { return planJson; }
}
