package com.kerosene.kfe.adapters.out.persistence.model.paymentexecution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KfeTransactionStatusTest {

    @Test
    void mapsCoarseDisplayStatus() {
        assertThat(KfeTransactionStatus.INTENT.displayStatus()).isEqualTo("PENDING");
        assertThat(KfeTransactionStatus.EXECUTING.displayStatus()).isEqualTo("PENDING");
        assertThat(KfeTransactionStatus.SETTLED.displayStatus()).isEqualTo("CONFIRMED");
        assertThat(KfeTransactionStatus.FAILED.displayStatus()).isEqualTo("FAILED");
        assertThat(KfeTransactionStatus.REQUIRES_RECONCILIATION.displayStatus()).isEqualTo("FAILED");
    }
}
