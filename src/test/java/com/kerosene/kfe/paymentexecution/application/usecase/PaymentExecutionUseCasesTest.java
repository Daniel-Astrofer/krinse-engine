package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionQueryRepository;
import com.kerosene.kfe.paymentexecution.application.query.GetPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.query.ListPaymentsQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentExecutionUseCasesTest {

    private final PaymentExecutionQueryRepository repository = mock(PaymentExecutionQueryRepository.class);

    @Test
    void getRejectsPaymentsOutsideParticipantVisibility() {
        PaymentExecutionId id = new PaymentExecutionId(UUID.randomUUID());
        when(repository.findParticipantVisibleById(42L, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new GetPaymentService(repository).get(new GetPaymentQuery(42L, id)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("KFE transaction not found.");
    }

    @Test
    void listBoundsPaginationBeforeCallingPersistence() {
        Instant since = Instant.parse("2026-09-08T12:00:00Z");
        PaymentExecutionResult result = mock(PaymentExecutionResult.class);
        when(repository.findParticipantVisible(42L, 0, 200, since)).thenReturn(List.of(result));

        var results = new ListPaymentsService(repository)
                .list(new ListPaymentsQuery(42L, -5, 1_000, since));

        assertThat(results).containsExactly(result);
        verify(repository).findParticipantVisible(42L, 0, 200, since);
    }

}
