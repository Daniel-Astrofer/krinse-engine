package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.ResolvePaymentWalletsCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRecipientDirectoryPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentWalletLookupPort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentWalletSelection;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentSelfTransferRejected;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentWalletSnapshot;
import java.util.UUID;

/** Payment-specific wallet decisions, independent of HTTP, JPA and the user-directory transport. */
public final class PaymentWalletsService {
    /** Loads owned source/destination wallets and acquires source locks where required. */
    private final PaymentWalletLookupPort wallets;
    /** Resolves internal recipient usernames to active user accounts. */
    private final PaymentRecipientDirectoryPort recipients;

    /** Creates payment-specific wallet decisions with wallet and recipient lookups. */
    /** @param wallets wallet ownership and spendability port @param recipients username-to-recipient directory port */
    public PaymentWalletsService(PaymentWalletLookupPort wallets, PaymentRecipientDirectoryPort recipients) {
        this.wallets = wallets;
        this.recipients = recipients;
    }

    /** Resolves an internal destination UUID from a supplied wallet ID, UUID reference, or username. */
    /** @param command payment direction, source/destination, and destination reference @return resolved destination wallet ID, or null when no destination reference is supplied */
    public UUID resolveDestinationReference(ResolvePaymentWalletsCommand command) {
        if (command.direction() != PaymentDirection.INTERNAL || command.destinationWalletId() != null) {
            return command.destinationWalletId();
        }
        String reference = normalize(command.externalReference());
        if (reference == null) { return null; }
        UUID id = parseUuid(reference);
        if (id != null) { return id; }
        var recipient = recipients.findByUsername(reference)
                .orElseThrow(() -> new IllegalArgumentException("Destination user not found."));
        if (!recipient.active()) { throw new IllegalArgumentException("Destination user is not active."); }
        return wallets.findForUserNewestFirst(recipient.userId()).stream()
                .filter(wallet -> wallet.userId() == recipient.userId())
                .filter(PaymentWalletSnapshot::usable).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Destination user has no active KFE wallet.")).id();
    }

    /** Rejects self-directed wallet transfers and external destinations that map to the source wallet. */
    /** @param command normalized payment wallet selection request @throws PaymentSelfTransferRejected when sender and destination resolve to the same owned wallet */
    public void requireNotSelfPayment(ResolvePaymentWalletsCommand command) {
        if (command.direction() == PaymentDirection.INTERNAL || command.rail() == PaymentRail.INTERNAL) {
            if (command.sourceWalletId() != null && command.sourceWalletId().equals(command.destinationWalletId())) {
                throw new PaymentSelfTransferRejected();
            }
            return;
        }
        if (command.direction() != PaymentDirection.OUTBOUND || command.externalReference() == null) { return; }
        String address = command.externalReference().trim();
        if (address.isEmpty()) { return; }
        wallets.findByAddress(address)
                .filter(wallet -> wallet.userId() == command.userId())
                .filter(wallet -> wallet.id().equals(command.sourceWalletId()))
                .ifPresent(wallet -> { throw new PaymentSelfTransferRejected(); });
    }

    /**
     * Loads and verifies owned, spendable source and destination snapshots, locking the source
     * when the rail requires an available-funds reservation.
     * @param command selection request produced by payment preflight
     * @return validated source/destination pair for pricing and settlement
     */
    public PaymentWalletSelection resolve(ResolvePaymentWalletsCommand command) {
        PaymentWalletSnapshot source = null;
        if (command.requiresSourceReserve()) {
            if (command.sourceWalletId() == null) { throw new IllegalArgumentException("sourceWalletId is required."); }
            source = wallets.lockOwnedSource(command.userId(), command.sourceWalletId())
                    .orElseThrow(() -> new IllegalArgumentException("Source KFE wallet not found."));
            if (!source.id().equals(command.sourceWalletId()) || source.userId() != command.userId()) {
                throw new IllegalArgumentException("Source KFE wallet not found.");
            }
            source.requireSpendable("source");
        }
        PaymentWalletSnapshot destination = null;
        if (command.direction() != PaymentDirection.OUTBOUND) {
            if (command.destinationWalletId() == null) { throw new IllegalArgumentException("destinationWalletId is required."); }
            var candidate = command.direction() == PaymentDirection.INBOUND
                    ? wallets.findOwnedDestination(command.userId(), command.destinationWalletId())
                    : wallets.findById(command.destinationWalletId());
            destination = candidate
                    .orElseThrow(() -> new IllegalArgumentException("Destination KFE wallet not found."));
            if (!destination.id().equals(command.destinationWalletId())) {
                throw new IllegalArgumentException("Destination KFE wallet not found.");
            }
            if (command.direction() == PaymentDirection.INBOUND && destination.userId() != command.userId()) {
                throw new IllegalArgumentException("Inbound destination wallet must belong to the authenticated user.");
            }
            destination.requireSpendable("destination");
        }
        return new PaymentWalletSelection(source, destination);
    }

    /** Trims a destination reference and removes repeated username sigils. */
    /** @param value raw username or wallet reference @return normalized value, or null for blank input */
    private static String normalize(String value) {
        if (value == null) { return null; }
        String normalized = value.trim();
        while (normalized.startsWith("@")) { normalized = normalized.substring(1).trim(); }
        return normalized.isBlank() ? null : normalized;
    }

    /** Parses a destination reference as a UUID without treating other references as errors. */
    /** @param reference normalized reference @return parsed UUID, or null when the reference is not a UUID */
    private static UUID parseUuid(String reference) {
        try { return UUID.fromString(reference); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
