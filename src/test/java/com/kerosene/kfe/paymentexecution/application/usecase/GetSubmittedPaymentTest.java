package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionQueryRepository;
import com.kerosene.kfe.paymentexecution.application.query.GetSubmittedPaymentQuery;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class GetSubmittedPaymentTest {
    @Test
    void recoveryIsAReadOnlyOwnedQuery() {
        var repository = mock(PaymentExecutionQueryRepository.class);
        var key = new IdempotencyKey("opaque-key");
        when(repository.findOwnedBySubmissionKey(42L, key)).thenReturn(Optional.empty());
        assertThat(new GetPaymentService(repository).findSubmitted(new GetSubmittedPaymentQuery(42L, key))).isEmpty();
        verify(repository).findOwnedBySubmissionKey(42L, key);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void invalidIdentityIsRejectedBeforeQueryingPersistence() {
        assertThatThrownBy(() -> new GetSubmittedPaymentQuery(0, new IdempotencyKey("key"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GetSubmittedPaymentQuery(42, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IdempotencyKey(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyKey("x".repeat(181))).isInstanceOf(IllegalArgumentException.class);
    }
}
