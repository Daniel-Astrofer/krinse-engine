package com.kerosene.kfe.adapters.in.http;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeReserveOverviewResponse;
import com.kerosene.kfe.ledger.adapters.in.KfeReserveOverviewService;

/** Serves administrator-facing reserve solvency snapshots through the standard API envelope. */
@RestController
@RequestMapping("/api/admin/kfe/reserves")
public class KfeReserveAdminController {

    /** Application service that calculates liabilities, assets, equity, coverage, and status. */
    private final KfeReserveOverviewService reserveOverviewService;

    /** @param reserveOverviewService reserve aggregation service used by the overview endpoint */
    public KfeReserveAdminController(KfeReserveOverviewService reserveOverviewService) {
        this.reserveOverviewService = reserveOverviewService;
    }

    /** @return success envelope containing the latest reserve solvency snapshot */
    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<KfeReserveOverviewResponse>> overview() {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE reserve overview retrieved.",
                reserveOverviewService.overview()));
    }
}
