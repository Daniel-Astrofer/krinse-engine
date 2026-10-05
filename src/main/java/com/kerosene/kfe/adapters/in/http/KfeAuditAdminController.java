package com.kerosene.kfe.adapters.in.http;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.kfe.adapters.in.http.dto.audit.KfeAuditEventResponse;
import com.kerosene.kfe.adapters.in.http.dto.audit.KfeAuditLatestResponse;
import com.kerosene.kfe.adapters.in.http.dto.audit.KfeAuditRootResponse;
import com.kerosene.kfe.audit.adapters.in.reporting.KfeAuditAdminService;

import java.util.List;
import java.util.UUID;

/** Administrative endpoints for inspecting and exporting the KFE audit chain. */
@RestController
@RequestMapping("/api/admin/kfe/audit")
@PreAuthorize("hasRole('ADMIN')")
public class KfeAuditAdminController {

    /** Application service that reads audit events and computes integrity roots. */
    private final KfeAuditAdminService auditAdminService;

    /** Creates the controller with its audit query service. */
    public KfeAuditAdminController(KfeAuditAdminService auditAdminService) {
        this.auditAdminService = auditAdminService;
    }

    /** Returns the latest persisted audit event and its chain root. */
    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<KfeAuditLatestResponse>> latest() {
        return ResponseEntity.ok(ApiResponse.success("KFE audit latest root retrieved.", auditAdminService.latest()));
    }

    /** Lists the newest audit events up to the caller-selected limit.
     * @param limit maximum number of events to return; defaults to 50
     */
    @GetMapping("/events")
    public ResponseEntity<ApiResponse<List<KfeAuditEventResponse>>> events(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.success("KFE audit events retrieved.", auditAdminService.events(limit)));
    }

    /** Lists audit events associated with one transaction.
     * @param transactionId transaction identifier whose event history is requested
     */
    @GetMapping("/transactions/{transactionId}")
    public ResponseEntity<ApiResponse<List<KfeAuditEventResponse>>> transactionEvents(
            @PathVariable UUID transactionId) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE transaction audit events retrieved.",
                auditAdminService.transactionEvents(transactionId)));
    }

    /** Computes an integrity root over the audit event history. */
    @PostMapping("/root")
    public ResponseEntity<ApiResponse<KfeAuditRootResponse>> root() {
        return ResponseEntity.ok(ApiResponse.success("KFE audit root computed.", auditAdminService.root()));
    }
}
