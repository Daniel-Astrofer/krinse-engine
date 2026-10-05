package com.kerosene.kfe.adapters.in.http;

import org.junit.jupiter.api.Test;
import com.kerosene.common.financial.operations.FinancialWalletProvisioningRequest;
import com.kerosene.kfe.adapters.out.integration.wallet.KfeFinancialWalletProvisioningAdapter;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KfeInternalWalletProvisioningControllerTest {

    private final KfeFinancialWalletProvisioningAdapter adapter = mock(KfeFinancialWalletProvisioningAdapter.class);
    private final KfeInternalWalletProvisioningController controller =
            new KfeInternalWalletProvisioningController(adapter);

    @Test
    void provisionsPrimaryWalletWhenCredentialMatches() {
        controller.ensurePrimaryWalletReady(new FinancialWalletProvisioningRequest(42L, "bc1qabc"));

        verify(adapter).ensurePrimaryWalletReady(42L, "bc1qabc");
    }

    @Test
    void rejectsMissingUserId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FinancialWalletProvisioningRequest(null, null));
    }
}
