package com.kerosene.kfe.liquidity.adapters.in.http;

import com.kerosene.kfe.liquidity.adapters.in.KfeTreasuryPlanningService;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeTreasuryProposalEntity;
import com.kerosene.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Protected by the existing /api/admin/kfe/** ADMIN security matcher. */
@RestController
@RequestMapping("/api/admin/kfe/treasury")
public class KfeTreasuryAdminController {
    private final KfeTreasuryPlanningService service;
    public KfeTreasuryAdminController(KfeTreasuryPlanningService service) { this.service=service; }
    @PostMapping("/proposals")
    public ApiResponse<KfeTreasuryProposalEntity> propose() {
        return ApiResponse.success("Treasury proposal recorded; no financial operation executed.",service.propose());
    }
    @GetMapping("/proposals")
    public ApiResponse<List<KfeTreasuryProposalEntity>> history(@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success("Treasury proposal history.",service.history(limit));
    }
}
