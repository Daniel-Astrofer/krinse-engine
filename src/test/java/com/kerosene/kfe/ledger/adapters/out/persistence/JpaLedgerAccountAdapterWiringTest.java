package com.kerosene.kfe.ledger.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceRepository;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.ledger.application.port.out.LedgerAccountPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JpaLedgerAccountAdapterWiringTest {
    @Test
    void componentUsesInjectedRepositoryAndHashServiceDespiteCompatibilityConstructor() {
        var repository = mock(KfeBalanceRepository.class);
        var hashes = mock(KfeHashService.class);
        var wallet = UUID.randomUUID();
        var entity = KfeBalanceEntity.empty(wallet, "BTC", "initial-hash");
        when(repository.findByWalletIdAndAssetForUpdate(wallet, "BTC"))
                .thenReturn(Optional.of(entity));
        when(hashes.balanceHash(entity)).thenReturn("injected-audit-hash");

        new ApplicationContextRunner()
                .withUserConfiguration(JpaLedgerAccountAdapter.class)
                .withBean(KfeBalanceRepository.class, () -> repository)
                .withBean(KfeHashService.class, () -> hashes)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(LedgerAccountPort.class);
                    var adapter = context.getBean(LedgerAccountPort.class);
                    adapter.save(adapter.lock(wallet, "BTC"));
                    verify(hashes).balanceHash(entity);
                    verify(repository).save(entity);
                    assertThat(entity.getLastHash()).isEqualTo("injected-audit-hash");
                    assertThat(entity.getBalanceSignature()).isEqualTo("injected-audit-hash");
                });
    }
}
