package com.kerosene.kfe.paymentrequest.adapters.in;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;
import com.kerosene.kfe.adapters.in.http.dto.paymentrequest.KfePublicPaymentRequestResponse;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningDestinationClassifier;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Platform-owned Lightning invoices must not be paid over LND by other Kerosene users
 * (same-node self-pay). In-app settlement uses the INTERNAL ledger instead.
 */
@Component
public class KfePlatformLightningPolicy {

    private final KfePaymentRequestRepository paymentRequestRepository;
    private final KfePaymentRequestService paymentRequestService;

    public KfePlatformLightningPolicy(
            KfePaymentRequestRepository paymentRequestRepository,
            KfePaymentRequestService paymentRequestService) {
        this.paymentRequestRepository = paymentRequestRepository;
        this.paymentRequestService = paymentRequestService;
    }

    /**
     * Looks up a platform payment request by BOLT11 or payment hash.
     * Returns empty when the destination is external (not ours).
     */
    public Optional<KfePublicPaymentRequestResponse> resolvePlatformInvoice(String invoiceOrHash) {
        return findEntity(invoiceOrHash)
                .map(entity -> paymentRequestService.publicGet(entity.getPublicId()));
    }

    /**
     * Hard deny for LIGHTNING OUTBOUND when {@code externalReference} is a platform invoice.
     */
    public void rejectLightningOutboundIfPlatformOwned(String externalReference) {
        Optional<KfePaymentRequestEntity> match = findEntity(externalReference);
        if (match.isEmpty()) {
            return;
        }
        KfePaymentRequestEntity pr = match.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("publicId", pr.getPublicId());
        data.put("walletId", pr.getWalletId() != null ? pr.getWalletId().toString() : null);
        data.put("amountSats", pr.getAmountSats());
        data.put("status", pr.getStatus() != null ? pr.getStatus().name() : null);
        data.put("rail", pr.getRail() != null ? pr.getRail().name() : null);
        data.put("paymentHash", pr.getPaymentHash());
        data.put("suggestedRail", "INTERNAL");
        data.put("suggestedDirection", "INTERNAL");
        throw new StructuredPlatformException(
                "Este invoice Lightning é da plataforma Kerosene. "
                        + "Pagamentos entre usuários usam o ledger INTERNAL "
                        + "(paymentRequestPublicId=" + pr.getPublicId() + "), não a rede Lightning.",
                HttpStatus.UNPROCESSABLE_ENTITY,
                ErrorCodes.LEDGER_PLATFORM_LIGHTNING_DENIED,
                data);
    }

    Optional<KfePaymentRequestEntity> findEntity(String invoiceOrHash) {
        if (invoiceOrHash == null || invoiceOrHash.isBlank()) {
            return Optional.empty();
        }
        String raw = invoiceOrHash.trim();

        // Normalize BOLT11 / lightning: wrappers via classifier when possible.
        LightningDestinationClassifier.Classified classified =
                LightningDestinationClassifier.classify(raw);
        String bolt11 = classified != null
                && classified.kind() == LightningDestinationClassifier.Kind.BOLT11
                ? classified.value()
                : null;

        if (bolt11 != null && !bolt11.isBlank()) {
            Optional<KfePaymentRequestEntity> byInvoice =
                    paymentRequestRepository.findFirstByPaymentRequestIgnoreCase(bolt11);
            if (byInvoice.isPresent()) {
                return byInvoice;
            }
        }

        Optional<KfePaymentRequestEntity> byRawInvoice =
                paymentRequestRepository.findFirstByPaymentRequestIgnoreCase(raw);
        if (byRawInvoice.isPresent()) {
            return byRawInvoice;
        }

        String hash = extractPaymentHash(raw, bolt11);
        if (hash != null && !hash.isBlank()) {
            return paymentRequestRepository.findFirstByPaymentHashIgnoreCase(hash);
        }
        return Optional.empty();
    }

    /**
     * True when an OPEN platform payment request carries this invoice/hash.
     */
    public boolean isOpenPlatformLightningInvoice(String invoiceOrHash) {
        return findEntity(invoiceOrHash)
                .filter(pr -> pr.getStatus() == KfePaymentRequestStatus.OPEN)
                .filter(pr -> pr.getRail() == KfeRail.LIGHTNING || hasBolt11(pr))
                .isPresent();
    }

    private static boolean hasBolt11(KfePaymentRequestEntity pr) {
        String bolt11 = pr.getPaymentRequest();
        return bolt11 != null && !bolt11.isBlank();
    }

    private static String extractPaymentHash(String raw, String bolt11) {
        String compact = raw.trim().toLowerCase(Locale.ROOT);
        if (compact.matches("[0-9a-f]{64}")) {
            return compact;
        }
        // Full BOLT11 embeds the hash in bech32; we rely on payment_request column match
        // for platform invoices (exact BOLT11 stored at creation time).
        return null;
    }
}
