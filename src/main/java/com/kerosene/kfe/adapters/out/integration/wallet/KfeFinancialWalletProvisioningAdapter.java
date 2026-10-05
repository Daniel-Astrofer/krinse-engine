package com.kerosene.kfe.adapters.out.integration.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.kerosene.common.exception.FinancialProviderUnavailableException;
import com.kerosene.common.financial.operations.FinancialWalletProvisioningPort;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeCreateWalletRequest;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeWalletResponse;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.wallet.adapters.in.KfeWalletService;
import com.kerosene.kfe.wallet.domain.model.WalletKind;
import com.kerosene.kfe.wallet.domain.model.WalletStatus;
import com.kerosene.kfe.wallet.domain.service.PrimaryWalletPolicy;

import java.util.List;

/**
 * KFE-side adapter for the Core -> KFE financial onboarding boundary.
 */
@Component
public class KfeFinancialWalletProvisioningAdapter implements FinancialWalletProvisioningPort {

    /** Logger for successful onboarding wallet creation without sensitive address details. */
    private static final Logger log = LoggerFactory.getLogger(KfeFinancialWalletProvisioningAdapter.class);
    /** Stable provider-unavailable message used when the primary wallet cannot become spendable. */
    private static final String PRIMARY_WALLET_NOT_READY = "Primary KFE wallet is not ready.";

    /** Wallet application facade used to find or create the user's internal primary wallet. */
    private final KfeWalletService kfeWalletService;

    /** @param kfeWalletService wallet application service that owns wallet creation and lookup */
    public KfeFinancialWalletProvisioningAdapter(KfeWalletService kfeWalletService) {
        this.kfeWalletService = kfeWalletService;
    }

    /**
     * Ensures the user has a ready internal primary wallet, creating one when none exists.
     * Existing internal wallets that are present but not ready fail closed rather than creating
     * a duplicate. A nonblank supplied address is normalized and used as the initial deposit address.
     *
     * @param userId owner for whom the primary wallet must be ready
     * @param initialAddress optional initial receiving address supplied by the onboarding flow
     * @throws FinancialProviderUnavailableException when an existing wallet is not ready or a new
     *         wallet fails the primary-wallet readiness policy
     */
    @Override
    public void ensurePrimaryWalletReady(Long userId, String initialAddress) {
        List<KfeWalletResponse> primaryWallets = kfeWalletService.listWallets(userId).stream()
                .filter(wallet -> wallet.kind() == KfeWalletKind.INTERNAL)
                .toList();
        if (primaryWallets.stream().anyMatch(this::isReady)) {
            return;
        }
        if (!primaryWallets.isEmpty()) {
            throw new FinancialProviderUnavailableException(PRIMARY_WALLET_NOT_READY);
        }
        String normalizedInitialAddress = blankToNull(initialAddress);
        KfeWalletResponse createdWallet = kfeWalletService.createWallet(
                userId,
                new KfeCreateWalletRequest(
                        KfeWalletKind.INTERNAL,
                        null,
                        "Conta Assegurada",
                        null,
                        null,
                        null,
                        null,
                        normalizedInitialAddress,
                        null,
                        null,
                        normalizedInitialAddress != null ? "SIGNUP_STATE_DEPOSIT_ADDRESS" : null,
                        false));
        if (!isReady(createdWallet)) {
            throw new FinancialProviderUnavailableException(PRIMARY_WALLET_NOT_READY);
        }
        log.info("[Onboarding] Primary KFE wallet created for userId={}", userId);
    }

    /** Applies the domain primary-wallet policy to the response's kind, status, and spendable flag. */
    private boolean isReady(KfeWalletResponse wallet) {
        return wallet != null && PrimaryWalletPolicy.isReady(
                WalletKind.valueOf(wallet.kind().name()),
                WalletStatus.valueOf(wallet.status().name()),
                wallet.spendable());
    }

    /** Trims a supplied address and maps null or whitespace-only input to null. */
    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
