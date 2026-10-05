package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.common.exception.FinancialSelfPaymentException;
import com.kerosene.common.financial.operations.FinancialUserDirectoryPort;
import com.kerosene.common.financial.operations.FinancialUserDirectoryPort.FinancialUserHandle;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentWalletLookupAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.remote.FinancialPaymentRecipientDirectoryAdapter;
import com.kerosene.kfe.paymentexecution.application.command.ResolvePaymentWalletsCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentWalletsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentWalletLookupPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRecipientDirectoryPort;
import com.kerosene.kfe.paymentexecution.config.PaymentWalletsConfiguration;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentWalletsAdapterTest {
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final KfeWalletAddressRepository addresses = mock(KfeWalletAddressRepository.class);
    private final FinancialUserDirectoryPort directory = mock(FinancialUserDirectoryPort.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final UUID sourceId = UUID.randomUUID();

    @Test
    void graphHasUniquePortsAndFinancialSelectionCannotRunStandalone() throws Exception {
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(PaymentWalletsUseCase.class)
                    .hasSingleBean(PaymentWalletLookupPort.class).hasSingleBean(PaymentRecipientDirectoryPort.class);
            assertThatThrownBy(() -> ctx.getBean(PaymentWalletsUseCase.class).resolve(outbound()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(PaymentWalletLookupPort.class).lockOwnedSource(7L, sourceId))
                    .isInstanceOf(IllegalTransactionStateException.class);
            verifyNoInteractions(wallets, addresses, directory, entityManager, connection);
        });
    }

    @Test
    void recipientPreflightDoesNotOpenAFinancialTransactionOrConnection() throws Exception {
        var connection = mock(Connection.class);
        var destination = wallet();
        destination.setUserId(8L);
        when(directory.findByUsername("Recipient")).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Optional.of(new FinancialUserHandle(8L, "Recipient", true));
        });
        when(wallets.findByUserIdOrderByCreatedAtDesc(8L)).thenReturn(List.of(destination));
        context(connection).run(ctx -> {
            assertThat(ctx.getBean(PaymentWalletsUseCase.class).resolveDestinationReference(
                    new ResolvePaymentWalletsCommand(7L, PaymentRail.INTERNAL, PaymentDirection.INTERNAL,
                            sourceId, null, " @ @ Recipient "))).isEqualTo(destination.getId());
            verifyNoInteractions(connection, entityManager);
        });
    }

    @Test
    void mapsSelfTransferRejectionToTheExistingPublicException() throws Exception {
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThatThrownBy(() -> ctx.getBean(PaymentWalletsUseCase.class).requireNotSelfPayment(
                    new ResolvePaymentWalletsCommand(7L, PaymentRail.INTERNAL, PaymentDirection.INTERNAL,
                            sourceId, sourceId, null)))
                    .isInstanceOf(FinancialSelfPaymentException.class)
                    .hasMessage("You cannot pay or send funds to yourself.");
            verifyNoInteractions(wallets, directory, connection);
        });
    }

    @Test
    void selectionAndSourceLookupJoinOneOwningTransaction() throws Exception {
        var connection = mock(Connection.class);
        var source = wallet();
        when(wallets.findByIdAndUserIdForUpdate(sourceId, 7L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return Optional.of(source);
        });
        context(connection).run(ctx -> {
            var tx = new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class));
            tx.executeWithoutResult(status -> {
                var selected = ctx.getBean(PaymentWalletsUseCase.class).resolve(outbound());
                assertThat(selected.source().id()).isEqualTo(sourceId);
                assertThat(selected.requiresSourceReserve()).isTrue();
                assertThatCode(() -> verify(connection, never()).commit()).doesNotThrowAnyException();
            });
            verify(connection).commit();
            verify(connection, never()).rollback();
        });
    }

    @Test
    void caughtInvalidWalletStillRollsBackTheFinancialTransaction() throws Exception {
        var connection = mock(Connection.class);
        var source = wallet();
        source.setStatus(KfeWalletStatus.FROZEN);
        when(wallets.findByIdAndUserIdForUpdate(sourceId, 7L)).thenReturn(Optional.of(source));
        context(connection).run(ctx -> {
            var tx = new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class));
            assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                    assertThatThrownBy(() -> ctx.getBean(PaymentWalletsUseCase.class).resolve(outbound()))
                            .isInstanceOf(IllegalStateException.class)))
                    .isInstanceOf(UnexpectedRollbackException.class);
            verify(connection).rollback();
            verify(connection, never()).commit();
        });
    }

    @Test
    void directoryFailureIsPropagatedWithoutOpeningFinancialTransaction() throws Exception {
        var connection = mock(Connection.class);
        var failure = new IllegalStateException("directory unavailable");
        when(directory.findByUsername("Recipient")).thenThrow(failure);
        context(connection).run(ctx -> {
            assertThatThrownBy(() -> ctx.getBean(PaymentWalletsUseCase.class).resolveDestinationReference(
                    new ResolvePaymentWalletsCommand(7L, PaymentRail.INTERNAL, PaymentDirection.INTERNAL,
                            sourceId, null, "@Recipient"))).isSameAs(failure);
            verifyNoInteractions(wallets, connection);
        });
    }

    private ApplicationContextRunner context(Connection connection) throws Exception {
        var ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        return new ApplicationContextRunner().withAllowCircularReferences(false).withUserConfiguration(Graph.class)
                .withBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(ds))
                .withBean(KfeWalletRepository.class, () -> wallets)
                .withBean(KfeWalletAddressRepository.class, () -> addresses)
                .withBean(FinancialUserDirectoryPort.class, () -> directory)
                .withBean(EntityManager.class, () -> entityManager);
    }

    private ResolvePaymentWalletsCommand outbound() {
        return new ResolvePaymentWalletsCommand(7L, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, sourceId, null, "address");
    }

    private KfeWalletEntity wallet() {
        var wallet = new KfeWalletEntity();
        wallet.setId(sourceId); wallet.setUserId(7L); wallet.setKind(KfeWalletKind.INTERNAL);
        wallet.setStatus(KfeWalletStatus.ACTIVE); wallet.setSpendable(true);
        return wallet;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({PaymentWalletsConfiguration.class, PaymentWalletsAdapter.class,
            JpaPaymentWalletLookupAdapter.class, FinancialPaymentRecipientDirectoryAdapter.class})
    static class Graph {}
}
