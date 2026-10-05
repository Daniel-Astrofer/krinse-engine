package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalPaymentSubmissionPreparationAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.crypto.LegacyPaymentProposalHashAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentSubmissionStateAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.telemetry.LegacyPaymentSubmissionTelemetryAdapter;
import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.*;
import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.application.result.*;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Spring graph/transaction proxies; PostgreSQL tests own assertions about SQL rollback. */
class PaymentSubmissionPreparationWiringTest {
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final EntityManager em = mock(EntityManager.class);
    private final PaymentWalletsUseCase wallets = mock(PaymentWalletsUseCase.class);
    private final PreparePaymentPricingUseCase pricing = mock(PreparePaymentPricingUseCase.class);
    private final PaymentSettlementGateUseCase gate = mock(PaymentSettlementGateUseCase.class);
    private final PaymentExecutionLifecycleUseCase lifecycle = mock(PaymentExecutionLifecycleUseCase.class);
    private final KfeHashService hashes = spy(new KfeHashService());
    private final KfeTransactionEntity tx = new KfeTransactionEntity();
    private final PaymentExecutionId id = new PaymentExecutionId(tx.getId());
    private final UUID source = UUID.randomUUID();
    private final UUID destination = UUID.randomUUID();
    private final PaymentWalletSnapshot destinationWallet = new PaymentWalletSnapshot(destination, 8L, true, false, true);

    @BeforeEach
    void ready() {
        tx.setUserId(7L);
        tx.setStatus(KfeTransactionStatus.INTENT);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);
        tx.setIdempotencyKey(" key ");
        tx.setSourceWalletId(source);
        tx.setDestinationWalletId(destination);
        tx.setGrossAmountSats(10_000L);
        tx.setExternalReference("reference");
        when(transactions.findByIdAndUserIdForUpdate(id.value(), 7L)).thenReturn(Optional.of(tx));
        when(transactions.findByIdAndUserId(id.value(), 7L)).thenReturn(Optional.of(tx));
        when(wallets.resolve(any())).thenReturn(new PaymentWalletSelection(
                new PaymentWalletSnapshot(source, 7L, true, false, true), destinationWallet));
        when(pricing.prepare(any())).thenReturn(new PaymentSubmissionPricing(300L,
                new PaymentPricingQuote(10_000L, 9_910L, 100L, 90L, 10_100L, 4),
                new PaymentDisplaySnapshot(BigDecimal.ONE, null, null, BigDecimal.TEN, null, null)));
        when(gate.requirePass(any())).thenReturn(new PaymentSettlementGateResult(3, 4));
        doAnswer(invocation -> {
            ExecutionStatus previous = ExecutionStatus.valueOf(tx.getStatus().name());
            ExecutionStatus target = invocation.getArgument(1);
            tx.setStatus(KfeTransactionStatus.valueOf(target.name()));
            return new PaymentExecutionStatusChanged(id, previous, target);
        }).when(lifecycle).transition(any(), any(), any(), any());
    }

    @Test
    void graphHasUniquePortsAndEveryEntryOrStateMutationRequiresAnExistingTransaction() throws Exception {
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(PreparePaymentSubmissionUseCase.class)
                    .hasSingleBean(PaymentSubmissionStatePort.class).hasSingleBean(PaymentProposalHashPort.class)
                    .hasSingleBean(PaymentSubmissionTelemetryPort.class);
            var state = ctx.getBean(PaymentSubmissionStatePort.class);
            assertThatThrownBy(() -> ctx.getBean(PreparePaymentSubmissionUseCase.class).prepare(command()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> state.lockAndLoad(7L, id)).isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> state.applyPricing(null, null)).isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> state.recordProposal(7L, id, "hash")).isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> state.recordQuorum(7L, id, 3)).isInstanceOf(IllegalTransactionStateException.class);
            verifyNoInteractions(transactions, em, wallets, pricing, gate, lifecycle, hashes, connection);
        });
    }

    @Test
    void realPricingAndHashAreVisibleToGateAndQuorumIsRecordedBeforeTheSingleCallerCommit() throws Exception {
        var connection = mock(Connection.class);
        String expectedHash = new KfeHashService().sha256(proposal().canonicalContent());
        doAnswer(invocation -> {
            assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.VALIDATING);
            assertThat(tx.getReceiverAmountSats()).isEqualTo(9_910L);
            assertThat(tx.getNetworkFeeSats()).isEqualTo(100L);
            assertThat(tx.getKeroseneFeeSats()).isEqualTo(90L);
            assertThat(tx.getTotalDebitSats()).isEqualTo(10_100L);
            assertThat(tx.getPricingPolicyVersion()).isEqualTo(4);
            assertThat(tx.getDisplayBtcUsd()).isEqualTo(BigDecimal.ONE);
            assertThat(tx.getDisplayAmountUsd()).isEqualTo(BigDecimal.TEN);
            assertThat(tx.getQuorumProposalHash()).isEqualTo(expectedHash);
            assertThat(tx.getQuorumAckCount()).isZero();
            verify(connection, never()).commit();
            return new PaymentSettlementGateResult(3, 4);
        }).when(gate).requirePass(any());
        doAnswer(invocation -> {
            assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.QUORUM_SYNC);
            assertThat(tx.getQuorumAckCount()).isEqualTo(3);
            return null;
        }).when(connection).commit();

        context(connection).run(ctx -> {
            var result = new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class))
                    .execute(status -> ctx.getBean(PreparePaymentSubmissionUseCase.class).prepare(command()));
            assertThat(result.transition()).isEqualTo(new PaymentExecutionStatusChanged(id, ExecutionStatus.VALIDATING, ExecutionStatus.QUORUM_SYNC));
            assertThat(result.destinationWallet()).isSameAs(destinationWallet);
            var order = inOrder(transactions, em, lifecycle, wallets, pricing, hashes, gate, connection);
            order.verify(transactions).findByIdAndUserIdForUpdate(id.value(), 7L);
            order.verify(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
            order.verify(lifecycle).transition(eq(id), eq(ExecutionStatus.VALIDATING), eq("KFE_TRANSACTION_VALIDATING"), any());
            order.verify(wallets).resolve(any());
            order.verify(pricing).prepare(any());
            order.verify(transactions).findByIdAndUserId(id.value(), 7L);
            order.verify(transactions).save(tx);
            order.verify(hashes).sha256(proposal().canonicalContent());
            order.verify(transactions).findByIdAndUserId(id.value(), 7L);
            order.verify(gate).requirePass(any());
            order.verify(lifecycle).transition(eq(id), eq(ExecutionStatus.QUORUM_SYNC), eq("KFE_TRANSACTION_QUORUM_SYNC"), any());
            order.verify(transactions).findByIdAndUserId(id.value(), 7L);
            order.verify(connection).commit();
            verify(connection, never()).rollback();
            verify(transactions, never()).saveAndFlush(any());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"lock", "wallets", "pricing-save", "gate", "quorum-lifecycle"})
    void caughtPreparationFailuresMarkTheCallerRollbackOnly(String stage) throws Exception {
        var failure = new IllegalStateException("preparation unavailable");
        switch (stage) {
            case "lock" -> doThrow(failure).when(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
            case "wallets" -> doThrow(failure).when(wallets).resolve(any());
            case "pricing-save" -> doThrow(failure).when(transactions).save(any());
            case "gate" -> doThrow(failure).when(gate).requirePass(any());
            case "quorum-lifecycle" -> doThrow(failure).when(lifecycle)
                    .transition(eq(id), eq(ExecutionStatus.QUORUM_SYNC), any(), any());
        }
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThatThrownBy(() -> new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status ->
                    assertThatThrownBy(() -> ctx.getBean(PreparePaymentSubmissionUseCase.class).prepare(command())).isSameAs(failure)))
                    .isInstanceOf(UnexpectedRollbackException.class);
            verify(connection).rollback();
            verify(connection, never()).commit();
            assertThat(tx.getQuorumAckCount()).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-ack", "unpersisted-state", "missing-row-after-transition"})
    void capturedFinalAcknowledgementOrStateFailureCannotCommitPreparation(String invalid) throws Exception {
        if (invalid.equals("invalid-ack")) {
            doAnswer(invocation -> {
                tx.setStatus(KfeTransactionStatus.QUORUM_SYNC);
                return new PaymentExecutionStatusChanged(id, ExecutionStatus.INTENT, ExecutionStatus.QUORUM_SYNC);
            }).when(lifecycle).transition(eq(id), eq(ExecutionStatus.QUORUM_SYNC), any(), any());
        } else if (invalid.equals("unpersisted-state")) {
            // A plausible event alone cannot replace the real state adapter's QUORUM_SYNC check.
            doReturn(new PaymentExecutionStatusChanged(id, ExecutionStatus.VALIDATING, ExecutionStatus.QUORUM_SYNC))
                    .when(lifecycle).transition(eq(id), eq(ExecutionStatus.QUORUM_SYNC), any(), any());
        } else {
            doAnswer(invocation -> tx.getStatus() == KfeTransactionStatus.QUORUM_SYNC ? Optional.empty() : Optional.of(tx))
                    .when(transactions).findByIdAndUserId(id.value(), 7L);
        }
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThatThrownBy(() -> new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status ->
                    assertThatThrownBy(() -> ctx.getBean(PreparePaymentSubmissionUseCase.class).prepare(command()))
                            .isInstanceOf(RuntimeException.class)))
                    .isInstanceOf(UnexpectedRollbackException.class);
            verify(gate).requirePass(any());
            verify(lifecycle).transition(eq(id), eq(ExecutionStatus.QUORUM_SYNC), any(), any());
            verify(connection).rollback();
            verify(connection, never()).commit();
            assertThat(tx.getQuorumAckCount()).isZero();
        });
    }

    @Test
    void aCaughtIntentReplayIsRejectedBeforeAnyFinancialPreparationAndMarksRollbackOnly() throws Exception {
        tx.setStatus(KfeTransactionStatus.QUORUM_SYNC);
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThatThrownBy(() -> new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status ->
                    assertThatThrownBy(() -> ctx.getBean(PreparePaymentSubmissionUseCase.class).prepare(command()))
                            .isInstanceOf(IllegalStateException.class)))
                    .isInstanceOf(UnexpectedRollbackException.class);
            verifyNoInteractions(wallets, pricing, gate, lifecycle, hashes);
            verify(connection).rollback();
            verify(connection, never()).commit();
            verify(transactions, never()).save(any());
        });
    }

    private PreparePaymentSubmissionCommand command() {
        return new PreparePaymentSubmissionCommand(7L, id, new RequestFingerprint("fingerprint"), 200L, 12L, 3, " reference ", null);
    }

    private PaymentProposal proposal() {
        return new PaymentProposal(id, 7L, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, source, destination,
                10_000L, 9_910L, 100L, 90L, 10_100L, " reference ", null);
    }

    private ApplicationContextRunner context(Connection connection) throws Exception {
        var dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        return new ApplicationContextRunner().withAllowCircularReferences(false).withUserConfiguration(Graph.class)
                .withBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
                .withBean(KfeTransactionRepository.class, () -> transactions).withBean(EntityManager.class, () -> em)
                .withBean(PaymentWalletsUseCase.class, () -> wallets).withBean(PreparePaymentPricingUseCase.class, () -> pricing)
                .withBean(PaymentSettlementGateUseCase.class, () -> gate).withBean(PaymentExecutionLifecycleUseCase.class, () -> lifecycle)
                .withBean(KfeHashService.class, () -> hashes);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({PaymentSubmissionPreparationConfiguration.class, TransactionalPaymentSubmissionPreparationAdapter.class,
            JpaPaymentSubmissionStateAdapter.class, LegacyPaymentProposalHashAdapter.class, LegacyPaymentSubmissionTelemetryAdapter.class})
    static class Graph {}
}
