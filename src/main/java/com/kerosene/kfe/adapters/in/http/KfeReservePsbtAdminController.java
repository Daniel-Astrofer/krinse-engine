package com.kerosene.kfe.adapters.in.http;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfePsbtWorkflowResponse;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeSignedPsbtRequest;
import com.kerosene.kfe.wallet.adapters.in.KfePsbtWorkflowService;

import java.util.List;
import java.util.UUID;

/** Provides authenticated administration of cold reserve PSBT signing workflows. */
@RestController
@RequestMapping("/api/admin/kfe/reserves/psbts")
public class KfeReservePsbtAdminController {

    /** Application service enforcing ownership and PSBT workflow state transitions. */
    private final KfePsbtWorkflowService psbtWorkflowService;

    /** @param psbtWorkflowService workflow service that owns reserve PSBT operations */
    public KfeReservePsbtAdminController(KfePsbtWorkflowService psbtWorkflowService) {
        this.psbtWorkflowService = psbtWorkflowService;
    }

    /**
     * Lists the authenticated user's workflows, optionally narrowed to one wallet.
     *
     * @param walletId optional wallet filter
     * @param authentication authenticated administrator identity
     * @return success envelope with matching workflow views
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<KfePsbtWorkflowResponse>>> list(
            @RequestParam(required = false) UUID walletId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE PSBT workflows retrieved.",
                psbtWorkflowService.list(KfeAuthenticationSupport.authenticatedUserId(authentication), walletId)));
    }

    /**
     * Loads one workflow belonging to the authenticated user.
     *
     * @param workflowId workflow identifier from the route
     * @param authentication authenticated administrator identity
     * @return success envelope with the requested workflow view
     */
    @GetMapping("/{workflowId}")
    public ResponseEntity<ApiResponse<KfePsbtWorkflowResponse>> get(
            @PathVariable UUID workflowId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE PSBT workflow retrieved.",
                psbtWorkflowService.get(KfeAuthenticationSupport.authenticatedUserId(authentication), workflowId)));
    }

    /**
     * Attaches an externally signed PSBT to the user's workflow for validation and state advancement.
     *
     * @param workflowId workflow identifier from the route
     * @param request validated signed-PSBT payload
     * @param authentication authenticated administrator identity
     * @return success envelope with the updated workflow state
     */
    @PostMapping("/{workflowId}/signed")
    public ResponseEntity<ApiResponse<KfePsbtWorkflowResponse>> signed(
            @PathVariable UUID workflowId,
            @Valid @RequestBody KfeSignedPsbtRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE signed PSBT accepted.",
                psbtWorkflowService.attachSignedPsbt(KfeAuthenticationSupport.authenticatedUserId(authentication), workflowId, request)));
    }

    /**
     * Broadcasts a workflow whose signature and policy checks have completed.
     *
     * @param workflowId workflow identifier from the route
     * @param authentication authenticated administrator identity
     * @return success envelope with the resulting workflow state
     */
    @PostMapping("/{workflowId}/broadcast")
    public ResponseEntity<ApiResponse<KfePsbtWorkflowResponse>> broadcast(
            @PathVariable UUID workflowId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE PSBT workflow broadcast.",
                psbtWorkflowService.broadcast(KfeAuthenticationSupport.authenticatedUserId(authentication), workflowId)));
    }
}
