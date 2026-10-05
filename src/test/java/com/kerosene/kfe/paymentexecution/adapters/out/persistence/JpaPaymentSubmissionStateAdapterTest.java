package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionStatePort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentDisplaySnapshot;
import com.kerosene.kfe.paymentexecution.application.result.PaymentPricingQuote;
import com.kerosene.kfe.paymentexecution.application.result.PaymentSubmissionPricing;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JpaPaymentSubmissionStateAdapterTest {
    private final KfeTransactionRepository repository = mock(KfeTransactionRepository.class);
    private final EntityManager em = mock(EntityManager.class);
    private final JpaPaymentSubmissionStateAdapter adapter = new JpaPaymentSubmissionStateAdapter(repository, em);
    private final KfeTransactionEntity tx = new KfeTransactionEntity();
    private final PaymentExecutionId id = new PaymentExecutionId(tx.getId());
    private final UUID source = UUID.randomUUID();
    private final UUID destination = UUID.randomUUID();

    @BeforeEach
    void ready() {
        tx.setUserId(7L); tx.setStatus(KfeTransactionStatus.INTENT);
        tx.setRail(KfeRail.ONCHAIN); tx.setDirection(KfeDirection.OUTBOUND);
        tx.setIdempotencyKey(" key "); tx.setSourceWalletId(source); tx.setDestinationWalletId(destination);
        tx.setGrossAmountSats(10_000L); tx.setExternalReference("normalized-reference"); tx.setMemo("unchanged memo");
        when(repository.findByIdAndUserIdForUpdate(id.value(), 7L)).thenReturn(Optional.of(tx));
        when(repository.findByIdAndUserId(id.value(), 7L)).thenReturn(Optional.of(tx));
    }

    @Test
    void loadUsesScopedLockAndRefreshesBeforeMappingPersistedState() {
        doAnswer(invocation -> {
            tx.setStatus(KfeTransactionStatus.VALIDATING);
            tx.setExternalReference("refreshed-reference");
            tx.setGrossAmountSats(20_000L);
            return null;
        }).when(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);

        assertThat(adapter.lockAndLoad(7L, id)).isEqualTo(new PaymentSubmissionSnapshot(id, 7L,
                ExecutionStatus.VALIDATING, new IdempotencyKey(" key "), PaymentRail.ONCHAIN,
                PaymentDirection.OUTBOUND, source, destination, 20_000L, "refreshed-reference"));
        var order = inOrder(repository, em);
        order.verify(repository).findByIdAndUserIdForUpdate(id.value(), 7L);
        order.verify(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "id", "owner", "null-owner"})
    void loadRejectsMissingOrMismatchedIdentityAfterRefresh(String invalid) {
        var requestedId = invalid.equals("id") ? new PaymentExecutionId(UUID.randomUUID()) : id;
        when(repository.findByIdAndUserIdForUpdate(requestedId.value(), 7L))
                .thenReturn(invalid.equals("missing") ? Optional.empty() : Optional.of(tx));
        doAnswer(invocation -> {
            if (invalid.equals("owner")) { tx.setUserId(8L); }
            if (invalid.equals("null-owner")) { tx.setUserId(null); }
            return null;
        }).when(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);

        assertThatThrownBy(() -> adapter.lockAndLoad(7L, requestedId)).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void refreshFailurePropagatesWithoutFallbackToUnrefreshedIntent() {
        var failure = new IllegalStateException("refresh unavailable");
        doThrow(failure).when(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);

        assertThatThrownBy(() -> adapter.lockAndLoad(7L, id)).isSameAs(failure);
        verify(repository, never()).findByIdAndUserId(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void pricingWritesEveryQuoteAndDisplayFieldOnlyAfterRevalidatingOriginalIntent() {
        var expected = snapshot();
        tx.setStatus(KfeTransactionStatus.VALIDATING);
        var pricing = pricing();

        adapter.applyPricing(expected, pricing);

        assertThat(tx.getGrossAmountSats()).isEqualTo(11_000L);
        assertThat(tx.getReceiverAmountSats()).isEqualTo(10_910L);
        assertThat(tx.getNetworkFeeSats()).isEqualTo(111L);
        assertThat(tx.getKeroseneFeeSats()).isEqualTo(90L);
        assertThat(tx.getTotalDebitSats()).isEqualTo(11_111L);
        assertThat(tx.getPricingPolicyVersion()).isEqualTo(6);
        assertThat(tx.getDisplayBtcUsd()).isEqualTo(new BigDecimal("60100.01"));
        assertThat(tx.getDisplayBtcEur()).isEqualTo(new BigDecimal("60200.02"));
        assertThat(tx.getDisplayBtcBrl()).isEqualTo(new BigDecimal("60300.03"));
        assertThat(tx.getDisplayAmountUsd()).isEqualTo(new BigDecimal("6.01"));
        assertThat(tx.getDisplayAmountEur()).isEqualTo(new BigDecimal("6.02"));
        assertThat(tx.getDisplayAmountBrl()).isEqualTo(new BigDecimal("6.03"));
        assertThat(tx.getIdempotencyKey()).isEqualTo(" key ");
        assertThat(tx.getExternalReference()).isEqualTo("normalized-reference");
        assertThat(tx.getMemo()).isEqualTo("unchanged memo");
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.VALIDATING);
        var order = inOrder(repository);
        order.verify(repository).findByIdAndUserId(id.value(), 7L);
        order.verify(repository).save(tx);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(em);
    }

    @Test
    void pricingPreservesNullableWalletAndPresentationFields() {
        tx.setSourceWalletId(null); tx.setDestinationWalletId(null); tx.setExternalReference(null);
        var expected = snapshot();
        tx.setStatus(KfeTransactionStatus.VALIDATING);
        var pricing = new PaymentSubmissionPricing(123L, pricing().quote(),
                new PaymentDisplaySnapshot(null, null, null, null, null, null));

        adapter.applyPricing(expected, pricing);

        assertThat(tx.getSourceWalletId()).isNull();
        assertThat(tx.getDestinationWalletId()).isNull();
        assertThat(tx.getDisplayBtcUsd()).isNull();
        assertThat(tx.getDisplayAmountUsd()).isNull();
        verify(repository).save(tx);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "id", "owner", "null-owner", "state", "key", "rail", "direction", "source", "destination", "amount", "reference"})
    void pricingRejectsChangedIdentityOrIntentBeforeWriting(String invalid) {
        var expected = snapshot();
        tx.setStatus(KfeTransactionStatus.VALIDATING);
        switch (invalid) {
            case "missing" -> when(repository.findByIdAndUserId(id.value(), 7L)).thenReturn(Optional.empty());
            case "id" -> when(repository.findByIdAndUserId(id.value(), 7L)).thenReturn(Optional.of(new KfeTransactionEntity()));
            case "owner" -> tx.setUserId(8L);
            case "null-owner" -> tx.setUserId(null);
            case "state" -> tx.setStatus(KfeTransactionStatus.QUORUM_SYNC);
            case "key" -> tx.setIdempotencyKey("other-key");
            case "rail" -> tx.setRail(KfeRail.LIGHTNING);
            case "direction" -> tx.setDirection(KfeDirection.INBOUND);
            case "source" -> tx.setSourceWalletId(UUID.randomUUID());
            case "destination" -> tx.setDestinationWalletId(UUID.randomUUID());
            case "amount" -> tx.setGrossAmountSats(9_999L);
            case "reference" -> tx.setExternalReference(" normalized-reference ");
        }
        assertThatThrownBy(() -> adapter.applyPricing(expected, pricing())).isInstanceOf(RuntimeException.class);
        assertThat(tx.getReceiverAmountSats()).isZero();
        assertThat(tx.getPricingPolicyVersion()).isZero();
        assertThat(tx.getDisplayBtcUsd()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void proposalAndQuorumAreManagedChangesOnlyInTheirExpectedStates() {
        tx.setStatus(KfeTransactionStatus.VALIDATING);
        adapter.recordProposal(7L, id, " proposal ");
        assertThat(tx.getQuorumProposalHash()).isEqualTo(" proposal ");
        tx.setStatus(KfeTransactionStatus.QUORUM_SYNC);
        adapter.recordQuorum(7L, id, 3);
        assertThat(tx.getQuorumAckCount()).isEqualTo(3);
        verify(repository, times(2)).findByIdAndUserId(id.value(), 7L);
        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(em);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void proposalRejectsAbsentHashBeforePersistence(String hash) {
        tx.setStatus(KfeTransactionStatus.VALIDATING);
        assertThatThrownBy(() -> adapter.recordProposal(7L, id, hash)).isInstanceOf(IllegalArgumentException.class);
        assertThat(tx.getQuorumProposalHash()).isNull();
        verifyNoInteractions(repository, em);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MAX_VALUE})
    void quorumCountRemainsTheGatesResponsibilityWithoutAddingANewPolicy(int ackCount) {
        tx.setStatus(KfeTransactionStatus.QUORUM_SYNC);
        adapter.recordQuorum(7L, id, ackCount);
        assertThat(tx.getQuorumAckCount()).isEqualTo(ackCount);
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"proposal-state", "quorum-state", "proposal-owner", "quorum-owner"})
    void proposalAndQuorumRejectWrongStateOrOwnerWithoutMutations(String invalid) {
        boolean proposal = invalid.startsWith("proposal");
        tx.setStatus(proposal ? KfeTransactionStatus.QUORUM_SYNC : KfeTransactionStatus.VALIDATING);
        if (invalid.endsWith("owner")) {
            tx.setStatus(proposal ? KfeTransactionStatus.VALIDATING : KfeTransactionStatus.QUORUM_SYNC);
            tx.setUserId(8L);
        }
        assertThatThrownBy(() -> {
            if (proposal) { adapter.recordProposal(7L, id, "proposal"); }
            else { adapter.recordQuorum(7L, id, 3); }
        }).isInstanceOf(RuntimeException.class);
        assertThat(tx.getQuorumProposalHash()).isNull();
        assertThat(tx.getQuorumAckCount()).isZero();
        verify(repository, never()).save(any());
    }

    @Test
    void invalidSelectorsFailBeforePersistence() {
        assertThatThrownBy(() -> adapter.lockAndLoad(0L, id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.lockAndLoad(7L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.recordProposal(-1L, id, "proposal")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.recordQuorum(7L, null, 3)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, em);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lock", "pricing", "proposal", "quorum"})
    void everyStateOperationRequiresTheOwningTransaction(String operation) throws Exception {
        var fixture = fixture();
        assertThatThrownBy(() -> invoke(fixture.port(), operation)).isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(repository, em, fixture.connection());
    }

    @ParameterizedTest
    @ValueSource(strings = {"lock", "pricing", "proposal", "quorum"})
    void aCaughtAdapterFailureStillPreventsTheOwningTransactionFromCommitting(String operation) throws Exception {
        var fixture = fixture();
        var failure = new IllegalStateException("persistence unavailable");
        if (operation.equals("lock")) {
            doThrow(failure).when(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        } else {
            when(repository.findByIdAndUserId(id.value(), 7L)).thenThrow(failure);
        }
        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                assertThatThrownBy(() -> invoke(fixture.port(), operation)).isSameAs(failure)))
                .isInstanceOf(UnexpectedRollbackException.class);
        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
    }

    private void invoke(PaymentSubmissionStatePort port, String operation) {
        switch (operation) {
            case "lock" -> port.lockAndLoad(7L, id);
            case "pricing" -> port.applyPricing(snapshot(), pricing());
            case "proposal" -> port.recordProposal(7L, id, "proposal");
            case "quorum" -> port.recordQuorum(7L, id, 3);
            default -> throw new IllegalArgumentException(operation);
        }
    }

    private PaymentSubmissionSnapshot snapshot() {
        return new PaymentSubmissionSnapshot(id, 7L, ExecutionStatus.INTENT, new IdempotencyKey(tx.getIdempotencyKey()),
                PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, tx.getSourceWalletId(), tx.getDestinationWalletId(),
                tx.getGrossAmountSats(), tx.getExternalReference());
    }

    private PaymentSubmissionPricing pricing() {
        return new PaymentSubmissionPricing(123L, new PaymentPricingQuote(11_000L, 10_910L, 111L, 90L, 11_111L, 6),
                new PaymentDisplaySnapshot(new BigDecimal("60100.01"), new BigDecimal("60200.02"), new BigDecimal("60300.03"),
                        new BigDecimal("6.01"), new BigDecimal("6.02"), new BigDecimal("6.03")));
    }

    private Fixture fixture() throws Exception {
        var connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        var dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        var manager = new DataSourceTransactionManager(dataSource);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(adapter);
        factory.addAdvice(interceptor);
        return new Fixture((PaymentSubmissionStatePort) factory.getProxy(), new TransactionTemplate(manager), connection);
    }

    private record Fixture(PaymentSubmissionStatePort port, TransactionTemplate transaction, Connection connection) {}
}
