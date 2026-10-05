package com.kerosene.kfe.wallet.adapters.in.http;

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
import com.kerosene.kfe.adapters.in.http.KfeAuthenticationSupport;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfePsbtWorkflowResponse;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeSignedPsbtRequest;
import com.kerosene.kfe.wallet.adapters.in.KfePsbtWorkflowService;

import java.util.List;
import java.util.UUID;

/** Authenticated endpoints for reviewing and advancing cold-wallet signing workflows. */
@RestController
@RequestMapping("/kfe/cold-wallet/psbts")
public class KfePsbtWorkflowController {

    /** Owner-scoped workflow service that validates signatures and broadcasts transactions. */
    private final KfePsbtWorkflowService psbtWorkflowService;

    /** Creates the workflow controller with its application facade. */
    public KfePsbtWorkflowController(KfePsbtWorkflowService psbtWorkflowService) {
        this.psbtWorkflowService = psbtWorkflowService;
    }

    /** Lists workflows for the principal, optionally narrowed to one wallet. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<KfePsbtWorkflowResponse>>> list(
            @RequestParam(required = false) UUID walletId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE PSBT workflows retrieved.",
                psbtWorkflowService.list(KfeAuthenticationSupport.authenticatedUserId(authentication), walletId)));
    }

    /** Retrieves a single workflow after the service verifies ownership. */
    @GetMapping("/{workflowId}")
    public ResponseEntity<ApiResponse<KfePsbtWorkflowResponse>> get(
            @PathVariable UUID workflowId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE PSBT workflow retrieved.",
                psbtWorkflowService.get(KfeAuthenticationSupport.authenticatedUserId(authentication), workflowId)));
    }

    /** Attaches a signed PSBT after validating that it still matches the approved payment. */
    @PostMapping("/{workflowId}/signed")
    public ResponseEntity<ApiResponse<KfePsbtWorkflowResponse>> signed(
            @PathVariable UUID workflowId,
            @Valid @RequestBody KfeSignedPsbtRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE signed PSBT accepted.",
                psbtWorkflowService.attachSignedPsbt(KfeAuthenticationSupport.authenticatedUserId(authentication), workflowId, request)));
    }

    /** Finalizes and broadcasts the owner-scoped workflow when policy permits. */
    @PostMapping("/{workflowId}/broadcast")
    public ResponseEntity<ApiResponse<KfePsbtWorkflowResponse>> broadcast(
            @PathVariable UUID workflowId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE PSBT workflow broadcast.",
                psbtWorkflowService.broadcast(KfeAuthenticationSupport.authenticatedUserId(authentication), workflowId)));
    }
}
