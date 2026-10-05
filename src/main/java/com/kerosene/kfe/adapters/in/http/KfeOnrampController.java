package com.kerosene.kfe.adapters.in.http;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.kfe.bootstrap.config.onramp.KfeOnrampDirectoryService;

import java.util.Map;

/** Serves configured on-ramp destinations used by clients that initiate external fiat purchases. */
@RestController
@RequestMapping("/kfe/transactions")
public class KfeOnrampController {

    /** Directory service that supplies the current on-ramp URL mapping. */
    private final KfeOnrampDirectoryService onrampDirectoryService;

    /** @param onrampDirectoryService configured on-ramp directory used to build the response */
    public KfeOnrampController(KfeOnrampDirectoryService onrampDirectoryService) {
        this.onrampDirectoryService = onrampDirectoryService;
    }

    /** @return success envelope containing provider names and their configured destination URLs */
    @GetMapping("/onramp-urls")
    public ResponseEntity<ApiResponse<Map<String, String>>> urls() {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE onramp URLs retrieved.",
                onrampDirectoryService.urls()));
    }
}
