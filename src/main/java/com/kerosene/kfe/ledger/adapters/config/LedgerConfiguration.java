package com.kerosene.kfe.ledger.adapters.config;

import com.kerosene.kfe.ledger.adapters.out.persistence.JpaLedgerAccountAdapter;
import com.kerosene.kfe.ledger.adapters.out.persistence.JpaLedgerPostingAdapter;
import com.kerosene.kfe.ledger.application.port.out.LedgerMutationObserver;
import com.kerosene.kfe.ledger.application.service.LedgerService;
import com.kerosene.kfe.ledger.application.service.LedgerSettlementService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Composition root for the extracted Ledger context, supplying its UTC clock and service beans.
 * Persistence adapters and mutation observation are injected at this boundary so application
 * services do not depend on Spring configuration details.
 */
@Configuration(proxyBeanMethods = false)
public class LedgerConfiguration {
    /** Creates the UTC clock shared by ledger timestamps and settlement transitions. */
    @Bean
    Clock ledgerClock() {
        return Clock.systemUTC();
    }

    /**
     * Creates the ledger mutation service with account/posting persistence and observers.
     *
     * @param accounts persistence port for ledger accounts
     * @param postings append/read adapter for ledger postings
     * @param observer observer for committed ledger mutations
     * @param ledgerClock UTC clock shared by ledger operations
     * @return configured ledger application service
     */
    @Bean
    LedgerService ledgerService(JpaLedgerAccountAdapter accounts, JpaLedgerPostingAdapter postings,
            LedgerMutationObserver observer, Clock ledgerClock) {
        return new LedgerService(accounts, postings, ledgerClock, observer);
    }

    /**
     * Creates the settlement-specific ledger service over the same persistence boundary and clock.
     *
     * @param accounts persistence port for ledger accounts
     * @param postings append/read adapter for ledger postings
     * @param ledgerClock UTC clock shared by settlement operations
     * @return configured ledger settlement service
     */
    @Bean
    LedgerSettlementService ledgerSettlementService(JpaLedgerAccountAdapter accounts,
            JpaLedgerPostingAdapter postings, Clock ledgerClock) {
        return new LedgerSettlementService(accounts, postings, ledgerClock);
    }
}
