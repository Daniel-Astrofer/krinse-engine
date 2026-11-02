package com.kerosene.kfe.liquidity.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.liquidity.*;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.*;
import com.kerosene.kfe.adapters.out.persistence.repository.liquidity.KfeChannelRebalanceJobRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.messaging.adapters.out.websocket.BalanceEventPublisher;
import com.kerosene.kfe.wallet.adapters.out.persistence.KfeSystemWalletService;
import jakarta.persistence.*;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Actual PostgreSQL row locks, transaction rollback and cross-worker fencing. Disposable DB only. */
@EnabledIfEnvironmentVariable(named="KFE_TREASURY_POSTGRES_URL",matches=".+")
class KfeRebalancePostgresTest {
    private static EntityManagerFactory emf;
    private static EntityManager em;
    private static TransactionTemplate tx;
    private static KfeChannelRebalanceJobRepository jobs;
    private static KfeBalanceRepository balances;
    private static KfeChannelRebalanceQueueService queue;
    private static KfeAuditLogService audit;
    private static final UUID wallet=UUID.randomUUID();

    @BeforeAll static void start() {
        emf=new Configuration().addAnnotatedClass(KfeChannelRebalanceJobEntity.class)
                .addAnnotatedClass(KfeBalanceEntity.class)
                .setProperty("hibernate.connection.url",System.getenv("KFE_TREASURY_POSTGRES_URL"))
                .setProperty("hibernate.connection.username","kfe_treasury_test")
                .setProperty("hibernate.connection.password","")
                .setProperty("hibernate.hbm2ddl.auto","create-drop")
                .setProperty("hibernate.hbm2ddl.create_namespaces","true")
                .buildSessionFactory();
        em=SharedEntityManagerCreator.createSharedEntityManager(emf);
        var tm=new JpaTransactionManager(emf); tx=new TransactionTemplate(tm);
        var factory=new JpaRepositoryFactory(em);
        jobs=factory.getRepository(KfeChannelRebalanceJobRepository.class);
        balances=factory.getRepository(KfeBalanceRepository.class);
        var hashes=mock(KfeHashService.class); when(hashes.balanceHash(any())).thenReturn("a".repeat(64));
        var walletService=mock(KfeSystemWalletService.class); when(walletService.requireProfitWalletId()).thenReturn(wallet);
        var balanceService=new KfeBalanceService(balances,hashes,mock(KfeWalletRepository.class),mock(BalanceEventPublisher.class));
        audit=mock(KfeAuditLogService.class);
        var target=new KfeChannelRebalanceQueueService(jobs,walletService,balanceService,audit);
        ProxyFactory proxy=new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(tm,new AnnotationTransactionAttributeSource()));
        queue=(KfeChannelRebalanceQueueService)proxy.getProxy();
        tx.executeWithoutResult(s -> em.createNativeQuery("CREATE UNIQUE INDEX test_unique_payment_hash ON financial.channel_rebalance_jobs(payment_hash) WHERE payment_hash IS NOT NULL").executeUpdate());
    }
    @AfterAll static void close() { if(emf!=null) emf.close(); }
    @BeforeEach void seed() {
        reset(audit);
        tx.executeWithoutResult(s -> {
            em.createQuery("delete from KfeChannelRebalanceJobEntity").executeUpdate();
            em.createQuery("delete from KfeBalanceEntity").executeUpdate();
            var b=KfeBalanceEntity.empty(wallet,"BTC","a".repeat(64)); b.setAvailableSats(10000); balances.saveAndFlush(b);
        });
    }
    private KfeChannelRebalanceJobEntity job() {
        return tx.execute(s -> { var j=new KfeChannelRebalanceJobEntity(); j.setChannelPoint(UUID.randomUUID()+":0"); return jobs.saveAndFlush(j); });
    }
    private KfeBalanceEntity balance() { return tx.execute(s -> balances.findById(new KfeBalanceId(wallet,"BTC")).orElseThrow()); }

    @Test void concurrentClaimsAndSettlementChargeExactlyOnce() throws Exception {
        var j=job(); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Optional<KfeChannelRebalanceQueueService.JobClaim>> attempt=() -> { start.await(); return queue.claim(j.getId(),"worker",600); };
            var a=pool.submit(attempt);var b=pool.submit(attempt); start.countDown();
            var claims=List.of(a.get(),b.get()).stream().flatMap(Optional::stream).toList();
            assertThat(claims).hasSize(1);
            var claim=claims.getFirst();
            assertThat(queue.beginDispatch(claim,"a".repeat(64),500)).isTrue();
            assertThat(queue.beginDispatch(claim,"a".repeat(64),500)).isFalse();
            assertThat(balance().getLockedSats()).isEqualTo(500);
            var f1=pool.submit(() -> queue.finishDispatch(claim,true,100));
            var f2=pool.submit(() -> queue.finishDispatch(claim,true,100));
            assertThat(List.of(f1.get(),f2.get())).containsExactlyInAnyOrder(true,false);
            assertThat(balance().getAvailableSats()).isEqualTo(9900);
            assertThat(balance().getLockedSats()).isZero();
        }
    }
    @Test void duplicateIdentityRollsBackReservationTogetherWithJob() {
        var first=queue.claim(job().getId(),"w1",600).orElseThrow();
        assertThat(queue.beginDispatch(first,"a".repeat(64),500)).isTrue();
        var second=queue.claim(job().getId(),"w2",600).orElseThrow();
        assertThatThrownBy(() -> queue.beginDispatch(second,"a".repeat(64),500)).isInstanceOf(RuntimeException.class);
        assertThat(balance().getAvailableSats()).isEqualTo(9500);
        assertThat(balance().getLockedSats()).isEqualTo(500);
        String phase=tx.execute(s -> jobs.findById(second.jobId()).orElseThrow().getExecutionPhase());
        assertThat(phase).isEqualTo("READY");
    }
    @Test void expiredClaimCannotReleaseReserveAndRecoveryCannotReserveAgain() {
        var claim=queue.claim(job().getId(),"w1",600).orElseThrow();
        queue.beginDispatch(claim,"a".repeat(64),500);
        tx.executeWithoutResult(s -> {var j=jobs.findForUpdate(claim.jobId()).orElseThrow();j.setLeaseExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));jobs.saveAndFlush(j);});
        assertThat(queue.finishDispatch(claim,false,0)).isFalse();
        assertThat(queue.pending(10)).extracting(KfeChannelRebalanceJobEntity::getId).contains(claim.jobId());
        var next=queue.claim(claim.jobId(),"w2",600).orElseThrow();
        assertThat(queue.beginDispatch(next,"b".repeat(64),500)).isFalse();
        assertThat(queue.failClaim(next,"timeout")).isFalse();
        assertThat(queue.finishDispatch(next,true,501)).isFalse();
        assertThat(queue.finishDispatch(next,true,-1)).isFalse();
        assertThat(balance().getLockedSats()).isEqualTo(500);
        assertThat(queue.finishDispatch(next,true,0)).isTrue();
        assertThat(balance().getAvailableSats()).isEqualTo(10000);
    }
    @Test void auditFailureRollsBackFeeSettlementAndCompletion() {
        var claim=queue.claim(job().getId(),"worker",600).orElseThrow();
        queue.beginDispatch(claim,"a".repeat(64),500);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any(),any(),any(),any(),any(),any());
        assertThatThrownBy(() -> queue.finishDispatch(claim,true,100)).isInstanceOf(IllegalStateException.class);
        assertThat(balance().getAvailableSats()).isEqualTo(9500);
        assertThat(balance().getLockedSats()).isEqualTo(500);
        var after=tx.execute(s -> jobs.findById(claim.jobId()).orElseThrow());
        assertThat(after.getStatus()).isEqualTo(KfeChannelRebalanceJobStatus.IN_PROGRESS);
        assertThat(after.getActualFeeSats()).isNull();
        assertThat(after.getReservedFeeSats()).isEqualTo(500);
    }

}
