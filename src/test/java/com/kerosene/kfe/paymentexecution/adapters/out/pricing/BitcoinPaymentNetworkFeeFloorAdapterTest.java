package com.kerosene.kfe.paymentexecution.adapters.out.pricing;

import com.kerosene.kfe.pricing.adapters.out.bitcoin.KfeNetworkFeeEstimateService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class BitcoinPaymentNetworkFeeFloorAdapterTest {

    private final KfeNetworkFeeEstimateService estimate = mock(KfeNetworkFeeEstimateService.class);
    private final BitcoinPaymentNetworkFeeFloorAdapter adapter = new BitcoinPaymentNetworkFeeFloorAdapter(estimate);

    @Test
    void delegatesRateAndTargetToReservationFloorWithoutRequestingAPublicQuote() {
        when(estimate.reservedFeeFloorSats(7L, 3)).thenReturn(2_100L);

        assertThat(adapter.minimumReserve(7L, 3)).isEqualTo(2_100L);

        verify(estimate).reservedFeeFloorSats(7L, 3);
        verifyNoMoreInteractions(estimate);
    }

    @Test
    void preservesNullableEstimatorDefaults() {
        when(estimate.reservedFeeFloorSats(null, null)).thenReturn(900L);

        assertThat(adapter.minimumReserve(null, null)).isEqualTo(900L);

        verify(estimate).reservedFeeFloorSats(null, null);
        verifyNoMoreInteractions(estimate);
    }

    @Test
    void doesNotHideProviderErrors() {
        var failure = new IllegalStateException("estimator unavailable");
        when(estimate.reservedFeeFloorSats(7L, 3)).thenThrow(failure);

        assertThatThrownBy(() -> adapter.minimumReserve(7L, 3)).isSameAs(failure);
    }
}
