package com.kerosene.kfe.adapters.in.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.common.financial.operations.FinancialWalletProvisioningRequest;
import com.kerosene.kfe.adapters.out.integration.wallet.KfeFinancialWalletProvisioningAdapter;

/** Receives authenticated service-to-service requests to ensure a user's primary KFE wallet exists. */
@RestController
@RequestMapping("/internal/kfe/wallet-provisioning")
public class KfeInternalWalletProvisioningController {

    /** Adapter that creates or repairs the requested user's primary financial wallet. */
    private final KfeFinancialWalletProvisioningAdapter walletProvisioningAdapter;

    public KfeInternalWalletProvisioningController(KfeFinancialWalletProvisioningAdapter walletProvisioningAdapter) {
        this.walletProvisioningAdapter = walletProvisioningAdapter;
    }

    /**
     * Ensures a primary wallet is ready for the requested user after authenticating the caller.
     *
     * @param request user identifier and optional initial wallet address
     * @return standard success envelope once provisioning is complete
     */
    @PostMapping("/primary")
    public ResponseEntity<ApiResponse<Void>> ensurePrimaryWalletReady(
            @RequestBody FinancialWalletProvisioningRequest request) {
        if (request == null || request.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        walletProvisioningAdapter.ensurePrimaryWalletReady(request.userId(), request.initialAddress());
        return ResponseEntity.ok(ApiResponse.success("Primary KFE wallet is ready.", null));
    }
}
