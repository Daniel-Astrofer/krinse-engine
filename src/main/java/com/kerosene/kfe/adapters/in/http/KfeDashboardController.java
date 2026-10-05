package com.kerosene.kfe.adapters.in.http;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.kfe.adapters.in.http.dto.ledger.KfeDashboardResponse;
import com.kerosene.kfe.audit.adapters.in.reporting.KfeDashboardService;

/** Serves the authenticated KFE dashboard projection for the current user. */
@RestController
@RequestMapping("/kfe")
public class KfeDashboardController {

    /** Query service that assembles dashboard wallets, balances, and statements. */
    private final KfeDashboardService dashboardService;

    /** Creates the dashboard controller with its application query service. */
    public KfeDashboardController(KfeDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** Returns dashboard data scoped to the authenticated user's account.
     * @param authentication authenticated Spring Security principal
     */
    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<KfeDashboardResponse>> dashboard(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE dashboard retrieved.",
                dashboardService.dashboard(KfeAuthenticationSupport.authenticatedUserId(authentication))));
    }
}
