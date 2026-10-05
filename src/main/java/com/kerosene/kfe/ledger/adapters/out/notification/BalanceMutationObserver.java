package com.kerosene.kfe.ledger.adapters.out.notification;

import com.kerosene.kfe.ledger.application.port.out.LedgerMutationObserver;
import com.kerosene.kfe.ledger.domain.LedgerBalance;
import com.kerosene.kfe.ledger.domain.LedgerBalance.Transition;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.messaging.adapters.out.websocket.BalanceEventPublisher;
import com.kerosene.kfe.messaging.adapters.out.websocket.BalanceUpdateEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Projects committed ledger transitions to the existing balance event contract.
 * Wallet lookup and websocket event construction remain outside the ledger domain model.
 */
@Component
public class BalanceMutationObserver implements LedgerMutationObserver {
    /** Wallet metadata source used to enrich events with user, label, and custody kind. */
    private final KfeWalletRepository wallets;
    /** Publisher that defers event delivery until the surrounding transaction commits. */
    private final BalanceEventPublisher events;

    /**
     * Creates the observer with wallet metadata and after-commit event publication adapters.
     *
     * @param wallets wallet repository used to resolve event recipient and display metadata
     * @param events publisher for committed balance updates
     */
    public BalanceMutationObserver(KfeWalletRepository wallets, BalanceEventPublisher events) {
        this.wallets = wallets;
        this.events = events;
    }

    /**
     * Loads wallet metadata after a ledger transition and publishes an event when the wallet exists.
     * Missing wallet metadata produces no event; transition behavior and balance mutation are not changed.
     *
     * @param balance post-transition ledger snapshot
     * @param transition applied movement type, amount, and bucket metadata
     */
    @Override
    public void afterApplied(LedgerBalance balance, Transition transition) {
        wallets.findById(balance.walletId()).ifPresent(wallet -> publish(wallet, balance, transition));
    }

    /**
     * Maps the transition to a signed display delta and publishes the relevant wallet balance projection.
     * WATCH_ONLY uses observed balance as its primary amount; other kinds use available balance.
     * Satoshis are converted to BTC decimal units for the event contract.
     *
     * @param wallet resolved wallet metadata
     * @param balance current ledger snapshot
     * @param transition applied movement details
     */
    private void publish(KfeWalletEntity wallet, LedgerBalance balance, Transition transition) {
        KfeWalletKind kind = wallet.getKind() == null ? KfeWalletKind.INTERNAL : wallet.getKind();
        long delta = switch (transition.movementType()) {
            case RESERVE -> Math.negateExact(transition.amountSats());
            case RELEASE_RESERVE, CREDIT, COMPENSATING_CREDIT -> transition.amountSats();
            case SETTLE_DEBIT -> 0L;
            default -> 0L;
        };
        long primary = kind == KfeWalletKind.WATCH_ONLY ? balance.observedSats() : balance.availableSats();
        events.publishBalanceUpdateAfterCommit(new BalanceUpdateEvent(
                wallet.getId().toString(), wallet.getLabel(), wallet.getUserId(),
                BigDecimal.valueOf(primary).movePointLeft(8), BigDecimal.valueOf(delta).movePointLeft(8),
                transition.movementType().name(), kind.name(), balance.availableSats(),
                balance.lockedSats(), balance.pendingSats(), balance.observedSats(), primary,
                transition.toBucket() == null ? "LOCKED" : transition.toBucket().name()));
    }
}
