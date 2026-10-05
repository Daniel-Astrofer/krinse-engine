package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentExecutionResultMapper;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionQueryRepository;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** JPA read adapter that keeps participant visibility enforcement inside the database query. */
@Component
public class JpaPaymentExecutionQueryAdapter implements PaymentExecutionQueryRepository {

    private final KfeTransactionRepository repository;
    private final KfeResponseMapper responseMapper;

    public JpaPaymentExecutionQueryAdapter(
            KfeTransactionRepository repository,
            KfeResponseMapper responseMapper) {
        this.repository = repository;
        this.responseMapper = responseMapper;
    }

    @Override
    public Optional<PaymentExecutionResult> findOwnedBySubmissionKey(long userId, IdempotencyKey key) {
        return repository.findByUserIdAndIdempotencyKey(userId, key.value())
                .map(entity -> responseMapper.toTransactionResponse(entity, userId))
                .map(LegacyPaymentExecutionResultMapper::toResult);
    }

    @Override
    public Optional<PaymentExecutionResult> findParticipantVisibleById(
            long userId,
            PaymentExecutionId paymentExecutionId) {
        return repository.findParticipantVisibleById(
                        paymentExecutionId.value(),
                        userId,
                        KfeRail.INTERNAL,
                        KfeDirection.INTERNAL)
                .map(entity -> responseMapper.toTransactionResponse(entity, userId))
                .map(LegacyPaymentExecutionResultMapper::toResult);
    }

    @Override
    public List<PaymentExecutionResult> findParticipantVisible(
            long userId,
            int page,
            int size,
            Instant since) {
        var pageable = PageRequest.of(page, size);
        var rows = since == null
                ? repository.findParticipantVisibleByUserId(
                        userId, KfeRail.INTERNAL, KfeDirection.INTERNAL, pageable)
                : repository.findParticipantVisibleByUserIdSince(
                        userId,
                        KfeRail.INTERNAL,
                        KfeDirection.INTERNAL,
                        LocalDateTime.ofInstant(since, ZoneOffset.UTC),
                        pageable);
        return rows.stream()
                .map(entity -> responseMapper.toTransactionResponse(entity, userId))
                .map(LegacyPaymentExecutionResultMapper::toResult)
                .toList();
    }
}
