package com.kerosene.kfe.adapters.out.persistence.repository.audit;

import com.kerosene.kfe.adapters.out.persistence.model.audit.KfeNetworkObservationLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** CRUD repository for append-only network observations used during settlement reconciliation. */
@Repository
public interface KfeNetworkObservationLogRepository
        extends JpaRepository<KfeNetworkObservationLogEntity, UUID> {
}
