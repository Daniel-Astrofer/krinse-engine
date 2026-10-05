package com.kerosene.kfe.wallet.adapters.in.http;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.kfe.adapters.in.http.KfeAuthenticationSupport;
import com.kerosene.kfe.wallet.adapters.in.FinancialApi;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeAddressResponse;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeColdWalletPsbtRequest;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeColdWalletPsbtResponse;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeCreateWalletRequest;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeUpdateWalletRequest;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeUtxoResponse;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeWalletNameOption;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeWalletResponse;

import java.util.List;
import java.util.UUID;

/** Authenticated HTTP endpoints for wallet lifecycle, receive addresses, UTXOs, and cold PSBTs. */
@RestController
@RequestMapping("/kfe/wallets")
public class KfeWalletController {

    /** Compatibility/application facade that performs wallet operations and response mapping. */
    private final FinancialApi financialApi;

    /** Creates the controller with the wallet facade used by each route. */
    public KfeWalletController(FinancialApi financialApi) {
        this.financialApi = financialApi;
    }

    /** Creates a wallet for the authenticated principal and returns HTTP 201 on success. */
    @PostMapping
    public ResponseEntity<ApiResponse<KfeWalletResponse>> create(
            @Valid @RequestBody KfeCreateWalletRequest request,
            Authentication authentication) {
        KfeWalletResponse response = financialApi.createWallet(KfeAuthenticationSupport.authenticatedUserId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("KFE wallet created.", response));
    }

    /** Lists wallet projections scoped to the authenticated principal. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<KfeWalletResponse>>> list(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE wallets retrieved.",
                financialApi.wallets(KfeAuthenticationSupport.authenticatedUserId(authentication))));
    }

    /** Returns the supported wallet-name options for client selection. */
    @GetMapping("/names")
    public ResponseEntity<ApiResponse<List<KfeWalletNameOption>>> names() {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE wallet names retrieved.",
                financialApi.walletNames()));
    }

    /** Updates a wallet label after deriving the owner from the authenticated principal. */
    @PatchMapping("/{walletId}")
    public ResponseEntity<ApiResponse<KfeWalletResponse>> update(
            @PathVariable UUID walletId,
            @Valid @RequestBody KfeUpdateWalletRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE wallet updated.",
                financialApi.updateWallet(KfeAuthenticationSupport.authenticatedUserId(authentication), walletId, request)));
    }

    /** Archives the principal's wallet while preserving its history. */
    @PostMapping("/{walletId}/archive")
    public ResponseEntity<ApiResponse<KfeWalletResponse>> archive(
            @PathVariable UUID walletId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE wallet archived.",
                financialApi.archiveWallet(KfeAuthenticationSupport.authenticatedUserId(authentication), walletId)));
    }

    /** Requests a new active receive address for the principal's wallet. */
    @PostMapping("/{walletId}/addresses/rotate")
    public ResponseEntity<ApiResponse<KfeAddressResponse>> rotateAddress(
            @PathVariable UUID walletId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE wallet address rotated.",
                financialApi.rotateAddress(KfeAuthenticationSupport.authenticatedUserId(authentication), walletId)));
    }

    /** Returns chain-observed UTXOs for the principal's wallet. */
    @GetMapping("/{walletId}/utxos")
    public ResponseEntity<ApiResponse<List<KfeUtxoResponse>>> listUtxos(
            @PathVariable UUID walletId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE wallet UTXOs retrieved.",
                financialApi.walletUtxos(KfeAuthenticationSupport.authenticatedUserId(authentication), walletId)));
    }

    /** Validates and prepares an unsigned cold-wallet PSBT for offline signing. */
    @PostMapping("/{walletId}/cold-wallet/psbt")
    public ResponseEntity<ApiResponse<KfeColdWalletPsbtResponse>> createColdWalletPsbt(
            @PathVariable UUID walletId,
            @Valid @RequestBody KfeColdWalletPsbtRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE cold wallet PSBT created.",
                financialApi.createColdWalletPsbt(KfeAuthenticationSupport.authenticatedUserId(authentication), walletId, request)));
    }
}
