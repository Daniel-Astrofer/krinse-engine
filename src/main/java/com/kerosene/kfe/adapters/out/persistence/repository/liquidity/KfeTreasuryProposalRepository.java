package com.kerosene.kfe.adapters.out.persistence.repository.liquidity;

import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeTreasuryProposalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.UUID;

public interface KfeTreasuryProposalRepository extends JpaRepository<KfeTreasuryProposalEntity, UUID> {
    List<KfeTreasuryProposalEntity> findAllByOrderByCreatedAtDesc(Pageable page);
}
