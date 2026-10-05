package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.ResolvePaymentWalletsCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRecipientDirectoryPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentWalletLookupPort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentRecipient;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentSelfTransferRejected;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentWalletSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PaymentWalletsServiceTest {
    private final PaymentWalletLookupPort wallets = mock(PaymentWalletLookupPort.class);
    private final PaymentRecipientDirectoryPort recipients = mock(PaymentRecipientDirectoryPort.class);
    private final PaymentWalletsService service = new PaymentWalletsService(wallets, recipients);

    @ParameterizedTest
    @ValueSource(strings = {"Nycollas", "@Nycollas", "  @ @ Nycollas  "})
    void resolvesNormalizedUsernameToFirstEligibleOwnedWallet(String reference) {
        var selected = wallet(42L);
        when(recipients.findByUsername("Nycollas")).thenReturn(Optional.of(new PaymentRecipient(42L, true)));
        when(wallets.findForUserNewestFirst(42L)).thenReturn(List.of(
                wallet(99L), new PaymentWalletSnapshot(UUID.randomUUID(), 42L, false, false, true),
                new PaymentWalletSnapshot(UUID.randomUUID(), 42L, true, true, true),
                new PaymentWalletSnapshot(UUID.randomUUID(), 42L, true, false, false), selected, wallet(42L)));

        assertThat(service.resolveDestinationReference(command(PaymentDirection.INTERNAL, UUID.randomUUID(), null, reference)))
                .isEqualTo(selected.id());
        verify(recipients).findByUsername("Nycollas");
        verify(wallets).findForUserNewestFirst(42L);
    }

    @Test
    void unknownUsernameFailsWithoutWalletLookup() {
        assertThatThrownBy(() -> service.resolveDestinationReference(command(PaymentDirection.INTERNAL, null, null, "missing")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Destination user not found.");
        verifyNoInteractions(wallets);
    }

    @Test
    void inactiveUserFailsWithoutWalletLookup() {
        when(recipients.findByUsername("disabled")).thenReturn(Optional.of(new PaymentRecipient(42L, false)));
        assertThatThrownBy(() -> service.resolveDestinationReference(command(PaymentDirection.INTERNAL, null, null, "@disabled")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Destination user is not active.");
        verifyNoInteractions(wallets);
    }

    @Test
    void recipientWithoutEligibleWalletFailsInsteadOfSelectingForeignWallet() {
        when(recipients.findByUsername("user")).thenReturn(Optional.of(new PaymentRecipient(42L, true)));
        when(wallets.findForUserNewestFirst(42L)).thenReturn(List.of(wallet(99L)));
        assertThatThrownBy(() -> service.resolveDestinationReference(command(PaymentDirection.INTERNAL, null, null, "user")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Destination user has no active KFE wallet.");
    }

    @Test
    void uuidReferenceSelectsIdWithoutTreatingItAsAuthorization() {
        UUID id = UUID.randomUUID();
        assertThat(service.resolveDestinationReference(command(PaymentDirection.INTERNAL, null, null, " @ " + id)))
                .isEqualTo(id);
        verifyNoInteractions(wallets, recipients);
    }

    @Test
    void explicitDestinationWinsWithoutResolvingDifferentReference() {
        UUID id = UUID.randomUUID();
        assertThat(service.resolveDestinationReference(command(PaymentDirection.INTERNAL, null, id, "@someone-else")))
                .isEqualTo(id);
        verifyNoInteractions(wallets, recipients);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "@", " @ @ "})
    void absentInternalReferenceIsLeftForRequiredDestinationValidation(String reference) {
        assertThat(service.resolveDestinationReference(command(PaymentDirection.INTERNAL, null, null, reference))).isNull();
        verifyNoInteractions(wallets, recipients);
    }

    @Test
    void externalSendDoesNotResolveUsernameOrUuidAsAnInternalWallet() {
        assertThat(service.resolveDestinationReference(command(PaymentDirection.OUTBOUND, null, null, "@recipient"))).isNull();
        verifyNoInteractions(wallets, recipients);
    }

    @Test
    void internalSameWalletIsRejectedWithoutLookingUpOwner() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.requireNotSelfPayment(command(PaymentDirection.INTERNAL, id, id, null)))
                .isInstanceOf(PaymentSelfTransferRejected.class);
        verifyNoInteractions(wallets, recipients);
    }

    @Test
    void internalTransferBetweenOwnWalletsIsAllowed() {
        service.requireNotSelfPayment(command(PaymentDirection.INTERNAL, UUID.randomUUID(), UUID.randomUUID(), null));
        verifyNoInteractions(wallets, recipients);
    }

    @Test
    void ownSourceAddressIsRejectedAfterWhitespaceNormalization() {
        var source = wallet(7L);
        when(wallets.findByAddress("source-address")).thenReturn(Optional.of(source));
        assertThatThrownBy(() -> service.requireNotSelfPayment(command(PaymentDirection.OUTBOUND, source.id(), null, "  source-address  ")))
                .isInstanceOf(PaymentSelfTransferRejected.class);
    }

    @ParameterizedTest
    @CsvSource({"7,false", "7,true", "42,false"})
    void outboundToOtherOwnWalletIncludingColdOrAnotherUserIsAllowed(long owner, boolean watchOnly) {
        var destination = new PaymentWalletSnapshot(UUID.randomUUID(), owner, true, watchOnly, !watchOnly);
        when(wallets.findByAddress("destination-address")).thenReturn(Optional.of(destination));
        service.requireNotSelfPayment(command(PaymentDirection.OUTBOUND, UUID.randomUUID(), null, "destination-address"));
    }

    @Test
    void externalUnknownAddressIsAllowed() {
        service.requireNotSelfPayment(command(PaymentDirection.OUTBOUND, UUID.randomUUID(), null, "external-address"));
        verify(wallets).findByAddress("external-address");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void absentExternalReferenceDoesNotQueryAddressDirectory(String reference) {
        service.requireNotSelfPayment(command(PaymentDirection.OUTBOUND, UUID.randomUUID(), null, reference));
        verifyNoInteractions(wallets, recipients);
    }

    @Test
    void outboundLocksOwnedSourceAndDoesNotLoadCallerSuppliedDestination() {
        var source = wallet(7L);
        when(wallets.lockOwnedSource(7L, source.id())).thenReturn(Optional.of(source));
        var selection = service.resolve(command(PaymentDirection.OUTBOUND, source.id(), UUID.randomUUID(), null));
        assertThat(selection.source()).isEqualTo(source);
        assertThat(selection.destination()).isNull();
        assertThat(selection.requiresSourceReserve()).isTrue();
        verify(wallets).lockOwnedSource(7L, source.id());
        verifyNoMoreInteractions(wallets);
        verifyNoInteractions(recipients);
    }

    @Test
    void inboundOnlyLoadsOwnedDestinationWithoutReservingCallerSuppliedSource() {
        var destination = wallet(7L);
        when(wallets.findOwnedDestination(7L, destination.id())).thenReturn(Optional.of(destination));
        var selection = service.resolve(command(PaymentDirection.INBOUND, UUID.randomUUID(), destination.id(), null));
        assertThat(selection.source()).isNull();
        assertThat(selection.destination()).isEqualTo(destination);
        assertThat(selection.requiresSourceReserve()).isFalse();
        verify(wallets).findOwnedDestination(7L, destination.id());
        verifyNoMoreInteractions(wallets);
    }

    @Test
    void inboundRejectsForeignDestination() {
        var destination = wallet(99L);
        when(wallets.findOwnedDestination(7L, destination.id())).thenReturn(Optional.of(destination));
        assertThatThrownBy(() -> service.resolve(command(PaymentDirection.INBOUND, null, destination.id(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Inbound destination wallet must belong to the authenticated user.");
    }

    @ParameterizedTest
    @ValueSource(longs = {7L, 99L})
    void internalAllowsOwnOrOtherRecipientUsingStoredOwner(long recipientId) {
        var source = wallet(7L);
        var destination = wallet(recipientId);
        when(wallets.lockOwnedSource(7L, source.id())).thenReturn(Optional.of(source));
        when(wallets.findById(destination.id())).thenReturn(Optional.of(destination));
        var selection = service.resolve(command(PaymentDirection.INTERNAL, source.id(), destination.id(), null));
        assertThat(selection.source()).isEqualTo(source);
        assertThat(selection.destination()).isEqualTo(destination);
        assertThat(selection.destination().userId()).isEqualTo(recipientId);
    }

    @Test
    void sourceAndDestinationIdsAreRequiredInTheirRespectiveDirections() {
        assertThatThrownBy(() -> service.resolve(command(PaymentDirection.OUTBOUND, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("sourceWalletId is required.");
        assertThatThrownBy(() -> service.resolve(command(PaymentDirection.INBOUND, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("destinationWalletId is required.");
        verifyNoInteractions(wallets, recipients);
    }

    @Test
    void unknownSourceAndDestinationAreRejected() {
        assertThatThrownBy(() -> service.resolve(command(PaymentDirection.OUTBOUND, UUID.randomUUID(), null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Source KFE wallet not found.");
        assertThatThrownBy(() -> service.resolve(command(PaymentDirection.INBOUND, null, UUID.randomUUID(), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Destination KFE wallet not found.");
    }

    @ParameterizedTest
    @CsvSource({"true,7", "false,99"})
    void forgedSourceIdOrOwnerReturnedByPortIsRejected(boolean wrongId, long owner) {
        UUID requestedId = UUID.randomUUID();
        var forged = new PaymentWalletSnapshot(wrongId ? UUID.randomUUID() : requestedId, owner, true, false, true);
        when(wallets.lockOwnedSource(7L, requestedId)).thenReturn(Optional.of(forged));
        assertThatThrownBy(() -> service.resolve(command(PaymentDirection.OUTBOUND, requestedId, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Source KFE wallet not found.");
    }

    @Test
    void forgedDestinationIdReturnedByPortIsRejected() {
        UUID requestedId = UUID.randomUUID();
        when(wallets.findOwnedDestination(7L, requestedId)).thenReturn(Optional.of(wallet(7L)));
        assertThatThrownBy(() -> service.resolve(command(PaymentDirection.INBOUND, null, requestedId, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Destination KFE wallet not found.");
    }

    @ParameterizedTest
    @CsvSource({"false,false,true", "true,true,true", "true,false,false"})
    void rejectsInactiveWatchOnlyOrNonSpendableSourceAndDestination(boolean active, boolean watchOnly, boolean spendable) {
        var wallet = new PaymentWalletSnapshot(UUID.randomUUID(), 7L, active, watchOnly, spendable);
        when(wallets.lockOwnedSource(7L, wallet.id())).thenReturn(Optional.of(wallet));
        when(wallets.findOwnedDestination(7L, wallet.id())).thenReturn(Optional.of(wallet));
        assertThatThrownBy(() -> service.resolve(command(PaymentDirection.OUTBOUND, wallet.id(), null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("source wallet");
        assertThatThrownBy(() -> service.resolve(command(PaymentDirection.INBOUND, null, wallet.id(), null)))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("destination wallet");
    }

    @Test
    void selectionUsesAlreadyAuthorizedWalletIdWithoutResolvingUsernameAgain() {
        var source = wallet(7L);
        var selected = wallet(42L);
        when(wallets.lockOwnedSource(7L, source.id())).thenReturn(Optional.of(source));
        when(wallets.findById(selected.id())).thenReturn(Optional.of(selected));
        assertThat(service.resolve(command(PaymentDirection.INTERNAL, source.id(), selected.id(), "@renamed-recipient")).destination())
                .isEqualTo(selected);
        verifyNoInteractions(recipients);
    }

    @Test
    void commandsRequireAuthenticationAndDirectionAndNeverPrintSensitiveReference() {
        assertThatThrownBy(() -> new ResolvePaymentWalletsCommand(0L, PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResolvePaymentWalletsCommand(7L, null, PaymentDirection.OUTBOUND, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResolvePaymentWalletsCommand(7L, PaymentRail.ONCHAIN, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(command(PaymentDirection.OUTBOUND, UUID.randomUUID(), null, "secret-invoice").toString())
                .contains("REDACTED").doesNotContain("secret-invoice");
    }

    private static PaymentWalletSnapshot wallet(long owner) {
        return new PaymentWalletSnapshot(UUID.randomUUID(), owner, true, false, true);
    }

    private static ResolvePaymentWalletsCommand command(PaymentDirection direction, UUID source, UUID destination, String reference) {
        return new ResolvePaymentWalletsCommand(7L, direction == PaymentDirection.INTERNAL ? PaymentRail.INTERNAL : PaymentRail.ONCHAIN,
                direction, source, destination, reference);
    }
}
