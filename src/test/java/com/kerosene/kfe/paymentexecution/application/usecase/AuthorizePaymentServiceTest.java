package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentApprovalPort;
import com.kerosene.kfe.paymentexecution.domain.exception.MissingLocalPaymentFactor;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorizePaymentServiceTest {
    private final PaymentApprovalPort approval = mock(PaymentApprovalPort.class);
    private final AuthorizePaymentService service = new AuthorizePaymentService(approval);

    @ParameterizedTest
    @CsvSource({"INTERNAL,INTERNAL", "INTERNAL,INBOUND", "INTERNAL,OUTBOUND", "ONCHAIN,INTERNAL", "LIGHTNING,INTERNAL", "ONCHAIN,OUTBOUND"})
    void existingLocalPolicyPassesTheExactCanonicalCommandForOneAtomicApproval(PaymentRail rail, PaymentDirection direction) {
        var command = command(rail, direction, " 0123 ");
        service.authorize(command);
        verify(approval).approve(same(command));
        verifyNoMoreInteractions(approval);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void missingLocalFactorFailsBeforeAnyApprovalCall(String pin) {
        assertThatThrownBy(() -> service.authorize(command(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, pin)))
                .isInstanceOf(MissingLocalPaymentFactor.class)
                .hasMessage("PIN do aplicativo obrigatorio para transacoes internas KFE e onchain custodial.");
        assertThatThrownBy(() -> service.authorize(command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, pin)))
                .isInstanceOf(MissingLocalPaymentFactor.class);
        verifyNoInteractions(approval);
    }

    @ParameterizedTest
    @CsvSource({"ONCHAIN,INBOUND", "LIGHTNING,INBOUND"})
    void inboundNeedsNoEndUserApprovalAndDoesNotRequirePin(PaymentRail rail, PaymentDirection direction) {
        service.authorize(command(rail, direction, null));
        verifyNoInteractions(approval);
    }

    @Test
    void lightningOutboundKeepsItsExistingStepUpFactorsAndDoesNotRequireLocalPin() {
        var command = command(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, null);
        service.authorize(command);
        verify(approval).approve(same(command));
        verifyNoMoreInteractions(approval);
    }

    @Test
    void missingAssertionRemainsTheBoundApprovalPortsResponsibility() {
        var command = new SubmitPaymentCommand(7L, new IdempotencyKey("key"), PaymentRail.INTERNAL, PaymentDirection.INTERNAL,
                null, null, 1L, 0L, null, null, null, null, null, "0123", null, null, null, null, null);
        service.authorize(command);
        verify(approval).approve(same(command));
        verifyNoMoreInteractions(approval);
    }

    @Test
    void approvalFailureDoesNotRetryOrFallThroughToAlternativeStepUp() {
        var failure = new IllegalStateException("approval denied");
        var command = command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, "0123");
        doThrow(failure).when(approval).approve(command);
        assertThatThrownBy(() -> service.authorize(command))
                .isSameAs(failure);
        verify(approval).approve(same(command));
        verifyNoMoreInteractions(approval);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void remoteAssertionOrOutboundFailurePropagatesWithoutLocalFallback(boolean custody) {
        var failure = new IllegalStateException("approval unavailable");
        var command = command(custody ? PaymentRail.ONCHAIN : PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, "0123");
        doThrow(failure).when(approval).approve(command);
        assertThatThrownBy(() -> service.authorize(command)).isSameAs(failure);
        verify(approval).approve(same(command));
        verifyNoMoreInteractions(approval);
    }

    @Test
    void missingCommandOrApprovalPortFailsClosed() {
        assertThatThrownBy(() -> service.authorize(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuthorizePaymentService(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(approval);
    }

    private SubmitPaymentCommand command(PaymentRail rail, PaymentDirection direction, String pin) {
        return new SubmitPaymentCommand(7L, new IdempotencyKey(" key "), rail, direction, UUID.randomUUID(), UUID.randomUUID(),
                10_000L, 100L, " reference ", " memo ", " 654321 ", " {assertion} ", " confirmation phrase ", pin,
                " public id ", 12L, 3, " quote id ", " device hash ");
    }
}
