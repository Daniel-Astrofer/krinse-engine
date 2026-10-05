package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class JpaPaymentRequestCancellationLockAdapterTest {
    private final KfePaymentRequestRepository repository = mock(KfePaymentRequestRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final JpaPaymentRequestCancellationLockAdapter adapter =
            new JpaPaymentRequestCancellationLockAdapter(repository, entityManager);

    @Test
    void scopedLockRefreshesPreviouslyManagedRequest() {
        UUID id = UUID.randomUUID();
        var request = new KfePaymentRequestEntity();
        request.setUserId(7L);
        when(repository.findByIdAndUserIdForUpdate(id, 7L)).thenReturn(Optional.of(request));

        adapter.lock(7L, id);

        var order = inOrder(repository, entityManager);
        order.verify(repository).findByIdAndUserIdForUpdate(id, 7L);
        order.verify(entityManager).refresh(request, LockModeType.PESSIMISTIC_WRITE);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void requestOutsideUserScopeIsNotLockedOrExposed() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> adapter.lock(7L, id))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE payment request not found.");
        verify(repository).findByIdAndUserIdForUpdate(id, 7L);
        verifyNoInteractions(entityManager);
    }

    @Test
    void refreshedOwnershipIsRechecked() {
        UUID id = UUID.randomUUID();
        var request = new KfePaymentRequestEntity();
        request.setUserId(7L);
        when(repository.findByIdAndUserIdForUpdate(id, 7L)).thenReturn(Optional.of(request));
        doAnswer(invocation -> {
            request.setUserId(8L);
            return null;
        }).when(entityManager).refresh(request, LockModeType.PESSIMISTIC_WRITE);

        assertThatThrownBy(() -> adapter.lock(7L, id))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE payment request not found.");
    }

    @Test
    void lockMustJoinFinancialTransaction() {
        assertThat(JpaPaymentRequestCancellationLockAdapter.class.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.MANDATORY);
    }
}
