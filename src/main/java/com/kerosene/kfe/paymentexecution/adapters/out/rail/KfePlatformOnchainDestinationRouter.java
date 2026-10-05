package com.kerosene.kfe.paymentexecution.adapters.out.rail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressRole;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.wallet.adapters.in.KfeWalletService;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * When a user sends <b>on-chain</b> to a Bitcoin address that already belongs to the Kerosene
 * platform (e.g. Conta Assegurada / payment-request address of another user), rewrite the
 * destination to that user's <b>real on-chain sink</b>:
 * <ol>
 *   <li>{@link KfeWalletKind#CUSTODIAL_ONCHAIN} (preferred — deposit observation + push)</li>
 *   <li>{@link KfeWalletKind#WATCH_ONLY} cold wallet (observed on-chain)</li>
 * </ol>
 *
 * <p>Without this, funds can land on INTERNAL ledger addresses that are not observed as custodial
 * deposits, so the recipient never sees balance/history/notification updates.
 */
@Service
public class KfePlatformOnchainDestinationRouter {

    private static final Logger log = LoggerFactory.getLogger(KfePlatformOnchainDestinationRouter.class);

    private final KfeWalletAddressRepository addressRepository;
    private final KfeWalletRepository walletRepository;
    private final KfeWalletService walletService;

    public KfePlatformOnchainDestinationRouter(
            KfeWalletAddressRepository addressRepository,
            KfeWalletRepository walletRepository,
            KfeWalletService walletService) {
        this.addressRepository = addressRepository;
        this.walletRepository = walletRepository;
        this.walletService = walletService;
    }

    /**
     * @return request unchanged, or with {@code externalReference} rewritten to the recipient's
     *     custodial/cold receive address.
     */
    public KfeSubmitTransactionRequest resolve(KfeSubmitTransactionRequest request) {
        if (request == null
                || request.rail() != KfeRail.ONCHAIN
                || request.direction() != KfeDirection.OUTBOUND) {
            return request;
        }
        String raw = request.externalReference() != null ? request.externalReference().trim() : "";
        if (raw.isEmpty()) {
            return request;
        }

        Optional<KfeWalletAddressEntity> hit = addressRepository.findFirstByAddressIgnoreCase(raw);
        if (hit.isEmpty()) {
            return request; // true external chain address
        }

        KfeWalletEntity taggedWallet = walletRepository.findById(hit.get().getWalletId()).orElse(null);
        if (taggedWallet == null || taggedWallet.getUserId() == null) {
            return request;
        }

        // Already a proper on-chain sink for that user — keep (custodial or cold).
        if (isOnchainSink(taggedWallet) && taggedWallet.getStatus() == KfeWalletStatus.ACTIVE) {
            log.debug(
                    "[KFE Onchain Route] platform address already on-chain sink walletId={} kind={}",
                    taggedWallet.getId(),
                    taggedWallet.getKind());
            return request;
        }

        KfeWalletEntity sink = resolvePreferredOnchainSink(taggedWallet.getUserId()).orElse(null);
        if (sink == null) {
            log.warn(
                    "[KFE Onchain Route] platform address for userId={} has no custodial/cold sink — keeping original",
                    taggedWallet.getUserId());
            return request;
        }

        // Never rewrite into the sender's same source wallet (self-loop).
        if (request.sourceWalletId() != null && request.sourceWalletId().equals(sink.getId())) {
            log.info(
                    "[KFE Onchain Route] sink equals source walletId={} — leave address for self-payment guard",
                    sink.getId());
            return request;
        }

        String receiveAddress = ensureReceiveAddress(sink);
        if (receiveAddress == null || receiveAddress.isBlank()) {
            log.warn(
                    "[KFE Onchain Route] could not resolve receive address for sink walletId={}",
                    sink.getId());
            return request;
        }
        if (receiveAddress.equalsIgnoreCase(raw)) {
            return request;
        }

        String memo = request.memo() != null && !request.memo().isBlank()
                ? request.memo()
                : "Envio on-chain (carteira Kerosene do destinatário)";
        log.info(
                "[KFE Onchain Route] rewrote platform dest userId={} fromKind={} toKind={} sinkWalletId={}",
                taggedWallet.getUserId(),
                taggedWallet.getKind(),
                sink.getKind(),
                sink.getId());
        return request.withExternalReference(receiveAddress).withMemo(memo);
    }

    /**
     * After broadcast, find the platform wallet that should observe this deposit (by dest address).
     */
    public Optional<UUID> findPlatformSinkWalletIdForAddress(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        return addressRepository.findFirstByAddressIgnoreCase(address.trim())
                .flatMap(row -> walletRepository.findById(row.getWalletId()))
                .filter(w -> w.getStatus() == KfeWalletStatus.ACTIVE)
                .filter(this::isOnchainSink)
                .map(KfeWalletEntity::getId);
    }

    /**
     * Resolve the recipient on-chain sink wallet for any known platform address
     * (INTERNAL → preferred custodial/cold of that user; custodial/cold → itself).
     */
    public Optional<UUID> resolveRecipientOnchainSinkWalletId(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        Optional<KfeWalletEntity> tagged = addressRepository.findFirstByAddressIgnoreCase(address.trim())
                .flatMap(row -> walletRepository.findById(row.getWalletId()));
        if (tagged.isEmpty() || tagged.get().getUserId() == null) {
            return Optional.empty();
        }
        KfeWalletEntity wallet = tagged.get();
        if (wallet.getStatus() == KfeWalletStatus.ACTIVE && isOnchainSink(wallet)) {
            return Optional.of(wallet.getId());
        }
        return resolvePreferredOnchainSink(wallet.getUserId()).map(KfeWalletEntity::getId);
    }

    private Optional<KfeWalletEntity> resolvePreferredOnchainSink(Long userId) {
        List<KfeWalletEntity> wallets = walletRepository.findByUserIdOrderByCreatedAtDesc(userId);
        // Prefer custodial (ledger credit + push), then cold watch-only.
        Optional<KfeWalletEntity> custodial = wallets.stream()
                .filter(w -> w.getStatus() == KfeWalletStatus.ACTIVE)
                .filter(w -> w.getKind() == KfeWalletKind.CUSTODIAL_ONCHAIN)
                .sorted(Comparator.comparing(KfeWalletEntity::getCreatedAt))
                .findFirst();
        if (custodial.isPresent()) {
            return custodial;
        }
        return wallets.stream()
                .filter(w -> w.getStatus() == KfeWalletStatus.ACTIVE)
                .filter(w -> w.getKind() == KfeWalletKind.WATCH_ONLY)
                .sorted(Comparator.comparing(KfeWalletEntity::getCreatedAt))
                .findFirst();
    }

    private boolean isOnchainSink(KfeWalletEntity wallet) {
        return wallet.getKind() == KfeWalletKind.CUSTODIAL_ONCHAIN
                || wallet.getKind() == KfeWalletKind.WATCH_ONLY;
    }

    private String ensureReceiveAddress(KfeWalletEntity sink) {
        List<KfeWalletAddressEntity> active =
                addressRepository.findByWalletIdAndStatusOrderByCreatedAtDesc(
                        sink.getId(), KfeWalletAddressStatus.ACTIVE);
        Optional<KfeWalletAddressEntity> receive = active.stream()
                .filter(a -> a.getAddress() != null && !a.getAddress().isBlank())
                .filter(a -> a.getAddressRole() == null
                        || a.getAddressRole() == KfeWalletAddressRole.RECEIVE
                        || a.getAddressRole() == KfeWalletAddressRole.MONITOR)
                .findFirst();
        if (receive.isPresent()) {
            return receive.get().getAddress().trim();
        }
        // Issue a fresh receive address for custodial (or cold with xpub).
        try {
            return walletService.ensureActiveReceiveAddress(sink.getUserId(), sink.getId());
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Onchain Route] issue receive address failed walletId={}: {}",
                    sink.getId(),
                    exception.getMessage());
            // Last resort: any active address on the wallet.
            return active.stream()
                    .map(KfeWalletAddressEntity::getAddress)
                    .filter(a -> a != null && !a.isBlank())
                    .map(a -> a.trim().toLowerCase(Locale.ROOT))
                    .findFirst()
                    .orElse(null);
        }
    }
}
