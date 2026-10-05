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
import com.kerosene.kfe.adapters.in.http.dto.ledger.KfeClassifyTaxEventRequest;
import com.kerosene.kfe.adapters.in.http.dto.ledger.KfeTaxEventResponse;
import com.kerosene.kfe.adapters.in.http.dto.ledger.KfeTaxEventsExportResponse;
import com.kerosene.kfe.audit.adapters.in.reporting.KfeTaxEventService;

import java.util.List;

/** Authenticated API for reviewing, exporting, and classifying a user's financial tax events. */
@RestController
@RequestMapping("/kfe/tax-events")
public class KfeTaxEventController {

    /** Application service that reads and classifies the user's tax event ledger. */
    private final KfeTaxEventService taxEventService;

    /** @param taxEventService tax event reporting and classification service */
    public KfeTaxEventController(KfeTaxEventService taxEventService) {
        this.taxEventService = taxEventService;
    }

    /** @param authentication authenticated caller whose tax events are listed
     *  @return success envelope containing the caller's tax event rows
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<KfeTaxEventResponse>>> list(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE tax events retrieved.",
                taxEventService.list(KfeAuthenticationSupport.authenticatedUserId(authentication))));
    }

    /**
     * Exports the authenticated user's tax events in the requested supported representation.
     *
     * @param format requested export format; defaults to JSON
     * @param authentication authenticated caller whose data is exported
     * @return success envelope containing the generated export metadata and content
     */
    @GetMapping("/export")
    public ResponseEntity<ApiResponse<KfeTaxEventsExportResponse>> export(
            @RequestParam(defaultValue = "json") String format,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE tax events export generated.",
                taxEventService.export(KfeAuthenticationSupport.authenticatedUserId(authentication), format)));
    }

    /**
     * Applies a validated user classification to one of the caller's tax events.
     *
     * @param eventId tax event identifier from the route
     * @param request classification data and any user-supplied explanation
     * @param authentication authenticated owner of the target event
     * @return success envelope containing the updated event representation
     */
    @PostMapping("/{eventId}/classify")
    public ResponseEntity<ApiResponse<KfeTaxEventResponse>> classify(
            @PathVariable String eventId,
            @Valid @RequestBody KfeClassifyTaxEventRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE tax event classified.",
                taxEventService.classify(KfeAuthenticationSupport.authenticatedUserId(authentication), eventId, request)));
    }
}
