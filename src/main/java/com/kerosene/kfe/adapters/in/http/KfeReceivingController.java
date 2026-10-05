package com.kerosene.kfe.adapters.in.http;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.kfe.wallet.adapters.in.FinancialApi;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeReceivingCapabilitiesResponse;

/** Provides public receiving-capability discovery for a receiver, optionally personalized to a sender. */
@RestController
@RequestMapping("/kfe")
public class KfeReceivingController {

    /** Compatibility facade that resolves the receiving options from the wallet application. */
    private final FinancialApi financialApi;

    /** @param financialApi application facade used to resolve supported receiving rails */
    public KfeReceivingController(FinancialApi financialApi) {
        this.financialApi = financialApi;
    }

    /**
     * Lists rails and payment details the receiver can currently accept.
     * When a caller is authenticated, its user ID is passed as sender context for eligibility rules.
     *
     * @param receiverIdentifier public receiver identifier from the route
     * @param authentication optional caller authentication; an invalid non-null identity is rejected
     * @return success envelope containing the receiver's available payment rails
     */
    @GetMapping("/users/{receiverIdentifier}/receiving-capabilities")
    public ResponseEntity<ApiResponse<KfeReceivingCapabilitiesResponse>> capabilities(
            @PathVariable String receiverIdentifier,
            Authentication authentication) {
        Long senderUserId = authentication == null
                ? null
                : KfeAuthenticationSupport.authenticatedUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                "KFE receiving capabilities retrieved.",
                financialApi.receivingCapabilities(senderUserId, receiverIdentifier)));
    }
}
