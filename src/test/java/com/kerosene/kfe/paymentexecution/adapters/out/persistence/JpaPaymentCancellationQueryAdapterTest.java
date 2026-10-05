package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationQueryPort;
import com.kerosene.kfe.paymentexecution.application.port.out.RelatedPaymentLookupPort;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentCancellationRejected;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class JpaPaymentCancellationQueryAdapterTest {

    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final KfePaymentRequestRepository requests = mock(KfePaymentRequestRepository.class);
    private final JpaPaymentCancellationQueryAdapter adapter = new JpaPaymentCancellationQueryAdapter(transactions, requests);

    @Test
    void invisibleTransactionReturnsNoProjectionAndDoesNotInspectRequestReferences() {
        var id = new PaymentExecutionId(UUID.randomUUID());

        assertThat(adapter.findParticipantVisible(7L, id)).isEmpty();

        verify(transactions).findParticipantVisibleById(id.value(), 7L, KfeRail.INTERNAL, KfeDirection.INTERNAL);
        verifyNoMoreInteractions(transactions);
        verifyNoInteractions(requests);
    }

    @Test
    void internalReceiverGetsNoSenderRequestMetadata() {
        var transaction = transaction(11L);
        transaction.setIdempotencyKey("payment-request:" + UUID.randomUUID() + ":sensitive-key");
        transaction.setExternalReference("sensitive-reference");
        visible(transaction, 7L);

        var projection = adapter.findParticipantVisible(7L, id(transaction)).orElseThrow();

        assertThat(projection.ownerUserId()).isEqualTo(11L);
        assertThat(projection.paymentRequest()).isNull();
        assertThat(projection.toString()).doesNotContain("sensitive-key", "sensitive-reference");
        verifyNoInteractions(requests);
    }

    @Test
    void paidTransactionLinkHasPriorityOverPublicReferenceAndIdempotencyKey() {
        var transaction = transaction(7L);
        transaction.setExternalReference("other-public-link");
        transaction.setIdempotencyKey("payment-request:" + UUID.randomUUID() + ":suffix");
        transaction.setBlockchainTxid("observed-txid");
        var request = request(7L);
        visible(transaction, 7L);
        when(requests.findByPaidTransactionIdAndUserId(transaction.getId(), 7L)).thenReturn(Optional.of(request));

        var projection = adapter.findParticipantVisible(7L, id(transaction)).orElseThrow();

        assertThat(projection.executionId()).isEqualTo(id(transaction));
        assertThat(projection.status()).isEqualTo(ExecutionStatus.VALIDATING);
        assertThat(projection.blockchainTransactionId()).isEqualTo("observed-txid");
        assertThat(projection.paymentRequest().id()).isEqualTo(request.getId());
        assertThat(projection.paymentRequest().publicId()).isEqualTo(request.getPublicId());
        verify(requests).findByPaidTransactionIdAndUserId(transaction.getId(), 7L);
        verifyNoMoreInteractions(requests);
    }

    @Test
    void scopedPublicReferenceHasPriorityOverIdempotencyKey() {
        var transaction = transaction(7L);
        transaction.setExternalReference("  own-public-link  ");
        transaction.setIdempotencyKey("payment-request:" + UUID.randomUUID() + ":suffix");
        var request = request(7L);
        visible(transaction, 7L);
        when(requests.findByPublicIdAndUserId("own-public-link", 7L)).thenReturn(Optional.of(request));

        var projection = adapter.findParticipantVisible(7L, id(transaction)).orElseThrow();

        assertThat(projection.paymentRequest().id()).isEqualTo(request.getId());
        var order = inOrder(requests);
        order.verify(requests).findByPaidTransactionIdAndUserId(transaction.getId(), 7L);
        order.verify(requests).findByPublicIdAndUserId("own-public-link", 7L);
        verifyNoMoreInteractions(requests);
    }

    @Test
    void idempotencyReferenceLookupIsAlwaysScopedToTheRequester() {
        var transaction = transaction(7L);
        var request = request(7L);
        transaction.setExternalReference("not-an-owned-public-link");
        transaction.setIdempotencyKey("payment-request:" + request.getId() + ":network-id");
        visible(transaction, 7L);
        when(requests.findByIdAndUserId(request.getId(), 7L)).thenReturn(Optional.of(request));

        var projection = adapter.findParticipantVisible(7L, id(transaction)).orElseThrow();

        assertThat(projection.paymentRequest().id()).isEqualTo(request.getId());
        var order = inOrder(requests);
        order.verify(requests).findByPaidTransactionIdAndUserId(transaction.getId(), 7L);
        order.verify(requests).findByPublicIdAndUserId("not-an-owned-public-link", 7L);
        order.verify(requests).findByIdAndUserId(request.getId(), 7L);
        verifyNoMoreInteractions(requests);
    }

    @Test
    void foreignRequestRowsCannotLeakMetadataEvenIfTheRepositoryViolatesItsScope() {
        var transaction = transaction(7L);
        var foreign = request(11L);
        transaction.setExternalReference(foreign.getPublicId());
        transaction.setIdempotencyKey("payment-request:" + foreign.getId() + ":network-id");
        visible(transaction, 7L);
        when(requests.findByPaidTransactionIdAndUserId(transaction.getId(), 7L)).thenReturn(Optional.of(foreign));
        when(requests.findByPublicIdAndUserId(foreign.getPublicId(), 7L)).thenReturn(Optional.of(foreign));
        when(requests.findByIdAndUserId(foreign.getId(), 7L)).thenReturn(Optional.of(foreign));

        assertThat(adapter.findParticipantVisible(7L, id(transaction)).orElseThrow().paymentRequest()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "customer-key", "payment-request:", "payment-request:not-a-uuid:suffix",
            "payment-request::suffix", "other-prefix:7c5896c5-29f4-49b5-b8ad-ab9724384d89:suffix"})
    void malformedOrUnrelatedIdempotencyKeysAreBenign(String key) {
        var transaction = transaction(7L);
        transaction.setIdempotencyKey(key);
        visible(transaction, 7L);

        assertThat(adapter.findParticipantVisible(7L, id(transaction)).orElseThrow().paymentRequest()).isNull();

        verify(requests).findByPaidTransactionIdAndUserId(transaction.getId(), 7L);
        verifyNoMoreInteractions(requests);
    }

    @ParameterizedTest
    @EnumSource(KfePaymentRequestStatus.class)
    void mapsEveryRequestStatusWithoutExportingTheJpaEntity(KfePaymentRequestStatus status) {
        var transaction = transaction(7L);
        var request = request(7L);
        request.setStatus(status);
        visible(transaction, 7L);
        when(requests.findByPaidTransactionIdAndUserId(transaction.getId(), 7L)).thenReturn(Optional.of(request));

        assertThat(adapter.findParticipantVisible(7L, id(transaction)).orElseThrow().paymentRequest().status())
                .isEqualTo(PaymentRequestCancellationStatus.valueOf(status.name()));
    }

    @Test
    void preservesNullStatusesForFailClosedEligibility() {
        var transaction = transaction(7L);
        transaction.setStatus(null);
        var request = request(7L);
        request.setStatus(null);
        visible(transaction, 7L);
        when(requests.findByPaidTransactionIdAndUserId(transaction.getId(), 7L)).thenReturn(Optional.of(request));

        var projection = adapter.findParticipantVisible(7L, id(transaction)).orElseThrow();

        assertThat(projection.status()).isNull();
        assertThat(projection.paymentRequest().status()).isNull();
    }

    @Test
    void relatedLookupKeepsEveryOwnedExecutionAndDeduplicatesAcrossAllSources() {
        var request = request(7L);
        var all = IntStream.range(0, 257).mapToObj(index -> transaction(7L)).toList();
        request.setPaidTransactionId(all.getFirst().getId());
        when(requests.findByIdAndUserId(request.getId(), 7L)).thenReturn(Optional.of(request));
        when(transactions.findByIdAndUserId(all.getFirst().getId(), 7L)).thenReturn(Optional.of(all.getFirst()));
        when(transactions.findByUserIdAndIdempotencyKeyStartingWith(7L, prefix(request))).thenReturn(all);
        when(transactions.findByUserIdAndExternalReference(7L, request.getPublicId()))
                .thenReturn(List.of(all.getFirst(), all.getLast()));

        var result = adapter.findRelated(7L, request.getId());

        assertThat(result).containsExactlyElementsOf(all.stream().map(JpaPaymentCancellationQueryAdapterTest::id).toList());
        assertThat(result).hasSize(257);
        assertThatThrownBy(() -> result.add(new PaymentExecutionId(UUID.randomUUID())))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(transactions).findByIdAndUserId(all.getFirst().getId(), 7L);
        verify(transactions).findByUserIdAndIdempotencyKeyStartingWith(7L, prefix(request));
        verify(transactions).findByUserIdAndExternalReference(7L, request.getPublicId());
        verifyNoMoreInteractions(transactions);
    }

    @Test
    void unknownPaidExecutionRejectsCancellationInsteadOfIgnoringPaymentEvidence() {
        var request = request(7L);
        request.setPaidTransactionId(UUID.randomUUID());
        when(requests.findByIdAndUserId(request.getId(), 7L)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> adapter.findRelated(7L, request.getId()))
                .isInstanceOf(PaymentCancellationRejected.class);

        verify(transactions).findByIdAndUserId(request.getPaidTransactionId(), 7L);
        verifyNoMoreInteractions(transactions);
    }

    @Test
    void foreignPaidExecutionReturnedByRepositoryStillRejectsCancellation() {
        var request = request(7L);
        var foreign = transaction(11L);
        request.setPaidTransactionId(foreign.getId());
        when(requests.findByIdAndUserId(request.getId(), 7L)).thenReturn(Optional.of(request));
        when(transactions.findByIdAndUserId(foreign.getId(), 7L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> adapter.findRelated(7L, request.getId()))
                .isInstanceOf(PaymentCancellationRejected.class);
    }

    @Test
    void foreignPrefixExecutionReturnedByRepositoryStillRejectsCancellation() {
        var request = request(7L);
        when(requests.findByIdAndUserId(request.getId(), 7L)).thenReturn(Optional.of(request));
        when(transactions.findByUserIdAndIdempotencyKeyStartingWith(7L, prefix(request)))
                .thenReturn(List.of(transaction(11L)));

        assertThatThrownBy(() -> adapter.findRelated(7L, request.getId()))
                .isInstanceOf(PaymentCancellationRejected.class);
    }

    @Test
    void foreignPublicReferenceExecutionReturnedByRepositoryStillRejectsCancellation() {
        var request = request(7L);
        when(requests.findByIdAndUserId(request.getId(), 7L)).thenReturn(Optional.of(request));
        when(transactions.findByUserIdAndExternalReference(7L, request.getPublicId()))
                .thenReturn(List.of(transaction(11L)));

        assertThatThrownBy(() -> adapter.findRelated(7L, request.getId()))
                .isInstanceOf(PaymentCancellationRejected.class);
    }

    @Test
    void absentRequestDoesNotInspectAnyExecution() {
        assertThatThrownBy(() -> adapter.findRelated(7L, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("KFE payment request not found.");

        verifyNoInteractions(transactions);
    }

    @Test
    void foreignRequestReturnedByRepositoryDoesNotInspectAnyExecution() {
        var foreign = request(11L);
        when(requests.findByIdAndUserId(foreign.getId(), 7L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> adapter.findRelated(7L, foreign.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("KFE payment request not found.");

        verifyNoInteractions(transactions);
    }

    @Test
    void blankPublicReferenceDoesNotQueryUnrelatedExecutions() {
        var request = request(7L);
        request.setPublicId(" ");
        when(requests.findByIdAndUserId(request.getId(), 7L)).thenReturn(Optional.of(request));

        assertThat(adapter.findRelated(7L, request.getId())).isEmpty();

        verify(transactions).findByUserIdAndIdempotencyKeyStartingWith(7L, prefix(request));
        verifyNoMoreInteractions(transactions);
    }

    @Test
    void relatedLookupRequiresTheOwningFinancialTransactionBeforeReading() throws Exception {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.related().findRelated(7L, UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(transactions, requests, fixture.connection());
    }

    @Test
    void projectionStartsAReadOnlyTransactionWhenCalledOutsideFinancialWork() throws Exception {
        var fixture = fixture();
        var id = new PaymentExecutionId(UUID.randomUUID());
        when(transactions.findParticipantVisibleById(id.value(), 7L, KfeRail.INTERNAL, KfeDirection.INTERNAL))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                    return Optional.empty();
                });

        assertThat(fixture.query().findParticipantVisible(7L, id)).isEmpty();

        verify(fixture.connection()).setReadOnly(true);
        verify(fixture.connection()).commit();
        verify(fixture.connection(), never()).rollback();
    }

    @Test
    void projectionJoinsAnExistingFinancialTransactionWithoutChangingItsReadOnlyMode() throws Exception {
        var fixture = fixture();
        var id = new PaymentExecutionId(UUID.randomUUID());
        when(transactions.findParticipantVisibleById(id.value(), 7L, KfeRail.INTERNAL, KfeDirection.INTERNAL))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
                    return Optional.empty();
                });

        fixture.transaction().executeWithoutResult(status -> fixture.query().findParticipantVisible(7L, id));

        verify(fixture.connection(), never()).setReadOnly(true);
        verify(fixture.connection()).commit();
    }

    @Test
    void unknownPaidExecutionMarksTheFinancialTransactionRollbackOnly() throws Exception {
        var fixture = fixture();
        var request = request(7L);
        request.setPaidTransactionId(UUID.randomUUID());
        when(requests.findByIdAndUserId(request.getId(), 7L)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> fixture.transaction().executeWithoutResult(status ->
                assertThatThrownBy(() -> fixture.related().findRelated(7L, request.getId()))
                        .isInstanceOf(PaymentCancellationRejected.class)))
                .isInstanceOf(UnexpectedRollbackException.class);

        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
    }

    private void visible(KfeTransactionEntity transaction, long userId) {
        when(transactions.findParticipantVisibleById(transaction.getId(), userId, KfeRail.INTERNAL, KfeDirection.INTERNAL))
                .thenReturn(Optional.of(transaction));
    }

    private static KfeTransactionEntity transaction(long userId) {
        var transaction = new KfeTransactionEntity();
        transaction.setUserId(userId);
        transaction.setStatus(KfeTransactionStatus.VALIDATING);
        transaction.setRail(KfeRail.ONCHAIN);
        transaction.setDirection(KfeDirection.INBOUND);
        return transaction;
    }

    private static KfePaymentRequestEntity request(long userId) {
        var request = new KfePaymentRequestEntity();
        request.setUserId(userId);
        request.setPublicId("own-public-link");
        request.setStatus(KfePaymentRequestStatus.OPEN);
        return request;
    }

    private static PaymentExecutionId id(KfeTransactionEntity transaction) {
        return new PaymentExecutionId(transaction.getId());
    }

    private static String prefix(KfePaymentRequestEntity request) {
        return "payment-request:" + request.getId() + ":";
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
        var proxy = factory.getProxy();
        return new Fixture((PaymentCancellationQueryPort) proxy, (RelatedPaymentLookupPort) proxy,
                new TransactionTemplate(manager), connection);
    }

    private record Fixture(PaymentCancellationQueryPort query, RelatedPaymentLookupPort related,
                           TransactionTemplate transaction, Connection connection) {}
}
