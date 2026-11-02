package com.kerosene.kfe.paymentrequest.adapters.in;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.paymentrequest.domain.model.KfePaymentBehaviorContract;
import com.kerosene.kfe.wallet.adapters.in.KfeWalletService;
import com.kerosene.kfe.wallet.adapters.out.bitcoin.KfeReceiveAddressIssuer;

import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentRequestCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CancelPaymentRequestUseCase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.common.service.AddressDerivationService;
import com.kerosene.kfe.adapters.in.http.dto.paymentrequest.KfeCreatePaymentRequest;
import com.kerosene.kfe.adapters.in.http.dto.paymentrequest.KfePaymentRequestResponse;
import com.kerosene.kfe.adapters.in.http.dto.paymentrequest.KfePublicPaymentRequestResponse;
import com.kerosene.kfe.adapters.in.http.dto.paymentrequest.RailDetail;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressRole;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.adapters.out.rail.custody.CustodyGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningInvoiceGateway;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.paymentrequest.adapters.out.webhook.KfeWebhookDeliveryService;
import com.kerosene.kfe.paymentrequest.domain.PaymentRequestLifecyclePolicy;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;

/** Creates, expires, hides, cancels, and projects participant/public payment requests across supported rails. */
@Service
public class KfePaymentRequestService {

    /** Logger for payment-request creation, address/provider failures, and compatibility fallbacks. */
    private static final Logger log = LoggerFactory.getLogger(KfePaymentRequestService.class);
    /** Cryptographically secure source of entropy for public request identifiers. */
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Entropy size in bytes used to create an unguessable public request identifier. */
    private static final int PUBLIC_ID_BYTES = 18;
    /** Recognizes supported extended public keys embedded in wallet descriptors. */
    private static final Pattern EXTENDED_PUBLIC_KEY_PATTERN = Pattern.compile(
            "([xtyzuv]pub[1-9A-HJ-NP-Za-km-z]+)");

    /** Persists and queries owner-scoped requests, public IDs, lifecycle, and rail payloads. */
    private final KfePaymentRequestRepository paymentRequestRepository;
    /** Resolves inbound settlement transactions linked to a payment request. */
    private final KfeTransactionRepository transactionRepository;
    /** Resolves wallet ownership and persists derivation-index advancement. */
    private final KfeWalletRepository walletRepository;
    /** Finds active receive addresses and stores newly derived addresses. */
    private final KfeWalletAddressRepository addressRepository;
    /** Ensures a wallet has a usable active receive address when request creation needs one. */
    private final KfeWalletService walletService;
    /** Derives receive addresses and child metadata from public extended keys. */
    private final AddressDerivationService addressDerivationService;
    /** Issues provider-backed receive addresses when wallet derivation material is unavailable. */
    private final KfeReceiveAddressIssuer receiveAddressIssuer;
    /** Records creation and lifecycle changes for financial audit. */
    private final KfeAuditLogService auditLogService;
    /** Schedules participant dashboard updates after request state changes. */
    private final KfeDashboardPublisher dashboardPublisher;
    /** External Lightning invoice issuer used for Lightning-capable requests. */
    private final LightningInvoiceGateway lightningInvoiceGateway;
    /** Application workflow that fences linked payments and cancels a request transactionally. */
    private final CancelPaymentRequestUseCase cancellation;
    /** Serializes multi-rail and behavior-contract payloads stored with the request. */
    private final ObjectMapper objectMapper;
    /** Optional webhook delivery service for lifecycle events. */
    private final KfeWebhookDeliveryService webhookDeliveryService;

    /** Supplies request persistence, wallet/address derivation, rail gateways, audit, and response dependencies. */
    public KfePaymentRequestService(
            KfePaymentRequestRepository paymentRequestRepository,
            KfeTransactionRepository transactionRepository,
            KfeWalletRepository walletRepository,
            KfeWalletAddressRepository addressRepository,
            KfeWalletService walletService,
            AddressDerivationService addressDerivationService,
            KfeReceiveAddressIssuer receiveAddressIssuer,
            KfeAuditLogService auditLogService,
            KfeDashboardPublisher dashboardPublisher,
            @Qualifier("kfeExternalLightningInvoiceGateway")
            LightningInvoiceGateway lightningInvoiceGateway,
            CancelPaymentRequestUseCase cancellation,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            KfeWebhookDeliveryService webhookDeliveryService) {
        this.paymentRequestRepository = paymentRequestRepository;
        this.transactionRepository = transactionRepository;
        this.walletRepository = walletRepository;
        this.addressRepository = addressRepository;
        this.walletService = walletService;
        this.addressDerivationService = addressDerivationService;
        this.receiveAddressIssuer = receiveAddressIssuer;
        this.auditLogService = auditLogService;
        this.dashboardPublisher = dashboardPublisher;
        this.lightningInvoiceGateway = lightningInvoiceGateway;
        this.cancellation = cancellation;
        this.objectMapper = objectMapper;
        this.webhookDeliveryService = webhookDeliveryService;
    }

    /** Validates owner and rail eligibility, provisions required on-chain/Lightning destinations, and persists the fixed/open amount contract with its public projection. */
    @Transactional
    public KfePaymentRequestResponse create(Long userId, KfeCreatePaymentRequest request) {
        validateCreateRequest(request);
        KfeWalletEntity wallet = walletRepository.findByIdAndUserId(request.walletId(), userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        List<KfeRail> rails = resolveRails(request);
        requireReceivingWallet(wallet, request, rails);

        KfeWalletAddressEntity onchainAddress = null;
        CustodyGateway.GeneratedLightningInvoice lightningInvoice = null;
        for (KfeRail r : rails) {
            if (r == KfeRail.ONCHAIN && onchainAddress == null) {
                onchainAddress = resolveReceivingAddress(userId, wallet, request);
            }
            if (r == KfeRail.LIGHTNING && lightningInvoice == null) {
                lightningInvoice = issueLightningInvoice(userId, wallet, request);
            }
        }

        KfeRail primaryRail = rails.stream()
                .filter(r -> r != KfeRail.INTERNAL)
                .findFirst()
                .orElse(KfeRail.INTERNAL);

        KfePaymentRequestEntity pr = new KfePaymentRequestEntity();
        pr.setPublicId(generatePublicId());
        pr.setUserId(userId);
        pr.setWalletId(wallet.getId());
        pr.setAddressId(onchainAddress == null ? null : onchainAddress.getId());

        if (lightningInvoice != null) {
            String hash = lightningInvoice.paymentHash();
            pr.setPaymentRequest(lightningInvoice.paymentRequest());
            pr.setPaymentHash(hash);
            pr.setProviderReference(lightningInvoice.providerReference());
            pr.setAddress(onchainAddress != null
                    ? onchainAddress.getAddress()
                    : shortLightningAddress(hash));
            if (request.expiresAt() == null && lightningInvoice.expiresAt() != null) {
                pr.setExpiresAt(lightningInvoice.expiresAt());
            }
        } else if (onchainAddress != null) {
            pr.setAddress(onchainAddress.getAddress());
        } else {
            pr.setAddress(internalWalletReference(wallet));
        }

        pr.setRail(primaryRail);
        pr.setStatus(KfePaymentRequestStatus.OPEN);
        pr.setAmountSats(request.amountSats());
        pr.setDescription(clean(request.description()));
        pr.setMemo(clean(request.memo()));
        pr.setPayerHint(clean(request.payerHint()));
        if (pr.getExpiresAt() == null) {
            pr.setExpiresAt(request.expiresAt());
        }
        pr.setRailsData(serializeRailsData(rails, onchainAddress, lightningInvoice));

        // Behavior contract: fixed-amount links use defaults; open-amount links accept partials.
        KfePaymentBehaviorContract contract;
        if (request.amountSats() != null && request.amountSats() > 0) {
            contract = KfePaymentBehaviorContract.forFixedAmount();
            pr.setPartialPaymentReceived(null);
        } else {
            contract = KfePaymentBehaviorContract.forOpenAmount();
            pr.setPartialPaymentReceived(0L);
        }
        pr.setBehaviorContract(serializeBehaviorContract(contract));

        // Webhook URL (optional)
        if (request.webhookUrl() != null && !request.webhookUrl().isBlank()) {
            pr.setWebhookUrl(request.webhookUrl().trim());
        }

        pr = paymentRequestRepository.save(pr);

        auditLogService.record(
                "KFE_PAYMENT_REQUEST_CREATED",
                null,
                wallet.getId(),
                null,
                null,
                Map.of(
                        "paymentRequestId", pr.getId().toString(),
                        "publicId", pr.getPublicId(),
                        "walletId", wallet.getId().toString(),
                        "rail", pr.getRail().name()));
        return toResponse(pr);
    }

    /** Returns the user's requests newest first, applying due-expiration transitions before mapping each response. */
    @Transactional
    public List<KfePaymentRequestResponse> list(Long userId) {
        return paymentRequestRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::expireIfDue)
                .map(this::toResponse)
                .toList();
    }

    /** Loads one request in the caller's owner scope and materializes expiration before returning it. */
    @Transactional
    public KfePaymentRequestResponse get(Long userId, UUID id) {
        KfePaymentRequestEntity paymentRequest = paymentRequestRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found."));
        return toResponse(expireIfDue(paymentRequest));
    }

    /** Loads a request by its unguessable public identifier and returns only its public projection. */
    @Transactional
    public KfePublicPaymentRequestResponse publicGet(String publicId) {
        KfePaymentRequestEntity paymentRequest = paymentRequestRepository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found."));
        return toPublicResponse(expireIfDue(paymentRequest));
    }

    /** Expires an owner-scoped OPEN request and audits the transition; other states remain unchanged. */
    @Transactional
    public KfePaymentRequestResponse expire(Long userId, UUID id) {
        KfePaymentRequestEntity paymentRequest = paymentRequestRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found."));
        if (paymentRequest.getStatus() == KfePaymentRequestStatus.OPEN) {
            paymentRequest.expire();
            paymentRequest = paymentRequestRepository.save(paymentRequest);
            auditStatusChange(paymentRequest, "KFE_PAYMENT_REQUEST_EXPIRED");
        }
        return toResponse(paymentRequest);
    }

    /** Hides a non-PAID owner request while preserving paid history and auditing the transition. */
    @Transactional
    public KfePaymentRequestResponse hide(Long userId, UUID id) {
        KfePaymentRequestEntity paymentRequest = paymentRequestRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found."));
        if (paymentRequest.getStatus() != KfePaymentRequestStatus.PAID) {
            paymentRequest.hide();
            paymentRequest = paymentRequestRepository.save(paymentRequest);
            auditStatusChange(paymentRequest, "KFE_PAYMENT_REQUEST_HIDDEN");
        }
        return toResponse(paymentRequest);
    }

    /** Delegates cancellation so linked payments are fenced and required Lightning cancellation is completed before returning refreshed state. */
    @Transactional
    public KfePaymentRequestResponse cancel(Long userId, UUID id) {
        // Full cancel: fence linked payments, require LN confirmation, then refresh the dashboard.
        cancellation.cancelPaymentRequest(new CancelPaymentRequestCommand(userId, id));
        return toResponse(paymentRequestRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found.")));
    }

    /** Reuses the active receive address unless the caller explicitly requests a fresh address. */
    private KfeWalletAddressEntity resolveReceivingAddress(
            Long userId,
            KfeWalletEntity wallet,
            KfeCreatePaymentRequest request) {
        if (Boolean.TRUE.equals(request.issueFreshAddress())) {
            return issueAddressWithoutRotation(wallet);
        }

        return addressRepository.findTopByWalletIdAndStatusOrderByCreatedAtDesc(
                        wallet.getId(),
                        KfeWalletAddressStatus.ACTIVE)
                .orElseGet(() -> issueAddressWithoutRotation(wallet));
    }

    /** Derives from an xpub or calls the configured issuer without retiring existing request addresses. */
    private KfeWalletAddressEntity issueAddressWithoutRotation(KfeWalletEntity wallet) {
        String xpub = receivingXpub(wallet);
        if (hasText(xpub)) {
            int nextIndex = wallet.getLastDerivedIndex() + 1;
            AddressDerivationService.DerivedAddress derived =
                    addressDerivationService.deriveAddressDetailsFromXpub(xpub, nextIndex);
            wallet.setLastDerivedIndex(nextIndex);
            walletRepository.save(wallet);
            return saveAddress(
                    wallet,
                    derived.address(),
                    "m/84'/0'/0'/0/" + nextIndex,
                    nextIndex,
                    "KFE_PAYMENT_REQUEST_XPUB_DERIVATION");
        }

        if (wallet.getKind() == KfeWalletKind.WATCH_ONLY) {
            throw new IllegalArgumentException("WATCH_ONLY wallets require an XPUB to issue fresh receiving addresses.");
        }

        KfeReceiveAddressIssuer.IssuedAddress issued = receiveAddressIssuer.issue(
                "kfe-payment-request-" + wallet.getId());
        if (issued.derivationIndex() >= 0) {
            wallet.setLastDerivedIndex(issued.derivationIndex());
            walletRepository.save(wallet);
        }
        return saveAddress(
                wallet,
                issued.address(),
                issued.derivationPath(),
                issued.derivationIndex() >= 0 ? issued.derivationIndex() : null,
                issued.providerReference());
    }

    /** Persists an active RECEIVE address with derivation and provider metadata for the owner wallet. */
    private KfeWalletAddressEntity saveAddress(
            KfeWalletEntity wallet,
            String addressValue,
            String derivationPath,
            Integer derivationIndex,
            String providerReference) {
        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setWalletId(wallet.getId());
        address.setAddress(addressValue);
        address.setAddressRole(KfeWalletAddressRole.RECEIVE);
        address.setStatus(KfeWalletAddressStatus.ACTIVE);
        address.setDerivationPath(derivationPath);
        address.setDerivationIndex(derivationIndex);
        address.setProviderReference(providerReference);
        return addressRepository.save(address);
    }

    /** Requires an active wallet and enforces custody, spendability, and receive-address prerequisites for selected rails. */
    private void requireReceivingWallet(
            KfeWalletEntity wallet,
            KfeCreatePaymentRequest request,
            List<KfeRail> rails) {
        if (wallet.getStatus() != KfeWalletStatus.ACTIVE) {
            throw new IllegalStateException("KFE wallet must be active to create a payment request.");
        }
        if (rails.size() == 1 && rails.get(0) == KfeRail.INTERNAL) {
            return;
        }
        boolean hasLightning = rails.contains(KfeRail.LIGHTNING);
        boolean hasOnchain = rails.contains(KfeRail.ONCHAIN) || rails.contains(KfeRail.INTERNAL);
        if (hasLightning) {
            if (wallet.getKind() == KfeWalletKind.WATCH_ONLY) {
                throw new IllegalArgumentException(
                        "WATCH_ONLY wallets cannot create Lightning payment requests.");
            }
            if (!wallet.isSpendable()) {
                throw new IllegalStateException("Wallet is not spendable for Lightning receiving.");
            }
        }
        if (!hasOnchain || wallet.getKind() != KfeWalletKind.WATCH_ONLY) {
            return;
        }
        if (hasText(receivingXpub(wallet))) {
            return;
        }
        boolean fresh = request != null && Boolean.TRUE.equals(request.issueFreshAddress());
        boolean active = addressRepository.findTopByWalletIdAndStatusOrderByCreatedAtDesc(
                wallet.getId(), KfeWalletAddressStatus.ACTIVE).isPresent();
        if (!fresh && active) return;
        if (fresh) return;
        throw new IllegalArgumentException(
                "WATCH_ONLY wallets require an xpub or active receiving address to create payment requests.");
    }

    /** Rejects missing wallets, nonpositive fixed amounts, past expiration, unsupported rails, and amountless Lightning requests. */
    private void validateCreateRequest(KfeCreatePaymentRequest request) {
        if (request == null || request.walletId() == null) {
            throw new IllegalArgumentException("KFE wallet id is required.");
        }
        if (request.amountSats() != null && request.amountSats() <= 0) {
            throw new IllegalArgumentException("KFE payment request amount must be positive when provided.");
        }
        if (request.expiresAt() != null
                && PaymentRequestLifecyclePolicy.isExpired(
                        request.expiresAt(), LocalDateTime.now(ZoneOffset.UTC))) {
            throw new IllegalArgumentException("KFE payment request expiration must be in the future.");
        }
        List<KfeRail> rails = resolveRails(request);
        for (KfeRail r : rails) {
            if (r != KfeRail.ONCHAIN && r != KfeRail.INTERNAL && r != KfeRail.LIGHTNING) {
                throw new IllegalArgumentException(
                        "KFE payment requests support INTERNAL, ONCHAIN and LIGHTNING receiving.");
            }
        }
        if (rails.contains(KfeRail.LIGHTNING)
                && (request.amountSats() == null || request.amountSats() <= 0L)) {
            throw new IllegalArgumentException("LIGHTNING payment requests require a positive amountSats.");
        }
    }

    /** Resolves rails from the request: prefers {@code rails} (list), falls back to legacy {@code rail}. */
    private List<KfeRail> resolveRails(KfeCreatePaymentRequest request) {
        if (request.rails() != null && !request.rails().isEmpty()) {
            List<KfeRail> unique = new ArrayList<>(EnumSet.copyOf(request.rails()));
            return unique.isEmpty() ? List.of(KfeRail.ONCHAIN) : List.copyOf(unique);
        }
        if (request.rail() != null) {
            return List.of(request.rail());
        }
        return List.of(KfeRail.ONCHAIN);
    }

    /** Requires a live provider, bounds invoice lifetime, and rejects empty invoices before linking provider identity. */
    private CustodyGateway.GeneratedLightningInvoice issueLightningInvoice(
            Long userId,
            KfeWalletEntity wallet,
            KfeCreatePaymentRequest request) {
        if (!lightningInvoiceGateway.isLive()) {
            throw new IllegalStateException("Lightning invoice gateway is not live.");
        }
        int expiresInSeconds = 3600;
        if (request.expiresAt() != null) {
            long seconds = Duration.between(LocalDateTime.now(java.time.ZoneOffset.UTC), request.expiresAt()).getSeconds();
            expiresInSeconds = (int) Math.max(60L, Math.min(seconds, 86_400L));
        }
        CustodyGateway.GeneratedLightningInvoice invoice = lightningInvoiceGateway.createLightningInvoice(
                new CustodyGateway.LightningInvoiceCommand(
                        userId,
                        null,
                        wallet.getLabel(),
                        request.amountSats(),
                        firstNonBlank(request.memo(), request.description(), "KFE payment request"),
                        expiresInSeconds));
        if (invoice == null
                || invoice.paymentRequest() == null
                || invoice.paymentRequest().isBlank()) {
            throw new IllegalStateException("Lightning invoice gateway returned an empty payment request.");
        }
        return invoice;
    }

    /** Builds a bounded display reference from a payment hash without returning a full invoice string. */
    private static String shortLightningAddress(String paymentHash) {
        if (paymentHash == null || paymentHash.isBlank()) {
            return "lightning:invoice";
        }
        String hash = paymentHash.trim();
        if (hash.length() > 100) {
            hash = hash.substring(0, 100);
        }
        return "ln:" + hash;
    }

    /** Returns the first nonblank candidate after trimming, or null when all options are absent. */
    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /** Creates a stable display URI identifying an internal wallet destination. */
    private String internalWalletReference(KfeWalletEntity wallet) {
        return "kerosene:wallet:" + wallet.getId();
    }

    /** Persists expiration only when the request is due and no settlement transaction already exists. */
    private KfePaymentRequestEntity expireIfDue(KfePaymentRequestEntity paymentRequest) {
        if (paymentRequest.isExpired(LocalDateTime.now(java.time.ZoneOffset.UTC)) && findSettlementTransaction(paymentRequest).isEmpty()) {
            paymentRequest.expire();
            return paymentRequestRepository.save(paymentRequest);
        }
        return paymentRequest;
    }

    /** Records request identity, public identifier, and new lifecycle status for an audit event. */
    private void auditStatusChange(KfePaymentRequestEntity paymentRequest, String eventType) {
        auditLogService.record(
                eventType,
                null,
                paymentRequest.getWalletId(),
                null,
                null,
                Map.of(
                        "paymentRequestId", paymentRequest.getId().toString(),
                        "publicId", paymentRequest.getPublicId(),
                        "status", paymentRequest.getStatus().name()));
    }

    /** Projects owner-visible request fields and the linked settlement transaction into the compatibility DTO. */
    private KfePaymentRequestResponse toResponse(KfePaymentRequestEntity entity) {
        KfeTransactionEntity settlementTx = findSettlementTransaction(entity).orElse(null);
        List<RailDetail> railDetails = deserializeRailsData(entity);
        return new KfePaymentRequestResponse(
                entity.getId(),
                entity.getPublicId(),
                entity.getUserId(),
                entity.getWalletId(),
                entity.getAddressId(),
                entity.getAddress(),
                entity.getPaymentRequest(),
                entity.getPaymentHash(),
                entity.getRail(),
                railDetails,
                entity.getStatus(),
                entity.getAmountSats(),
                entity.getDescription(),
                entity.getMemo(),
                entity.getPayerHint(),
                entity.getPaidTransactionId(),
                settlementTx == null ? null : settlementTx.getId(),
                settlementTx == null ? null : settlementTx.getStatus(),
                settlementTx == null ? null : settlementTx.getBlockchainTxid(),
                settlementTx == null ? 0 : settlementTx.getConfirmations(),
                settlementTx == null ? null : settlementTx.getGrossAmountSats(),
                settlementTx == null ? null : settlementTx.getReceiverAmountSats(),
                com.kerosene.kfe.bootstrap.time.Utc.toInstant(entity.getExpiresAt()),
                com.kerosene.kfe.bootstrap.time.Utc.toInstant(entity.getCreatedAt()),
                com.kerosene.kfe.bootstrap.time.Utc.toInstant(entity.getUpdatedAt()),
                entity.getBehaviorContract(),
                entity.getPartialPaymentReceived(),
                entity.getWebhookUrl());
    }

    /** Projects only public request details, requested unit, available rails, and public timestamps. */
    private KfePublicPaymentRequestResponse toPublicResponse(KfePaymentRequestEntity entity) {
        List<RailDetail> railDetails = deserializeRailsData(entity);
        List<String> rails = railDetails.stream()
                .map(r -> r.rail().name())
                .distinct()
                .toList();
        KfePaymentRequestStatus status = entity.getStatus();
        return new KfePublicPaymentRequestResponse(
                entity.getPublicId(),
                entity.getDescription(),
                entity.getAmountSats() != null ? BigDecimal.valueOf(entity.getAmountSats()) : null,
                "SATS",
                entity.getMemo(),
                status,
                com.kerosene.kfe.bootstrap.time.Utc.toInstant(entity.getExpiresAt()),
                rails,
                com.kerosene.kfe.bootstrap.time.Utc.toInstant(entity.getCreatedAt()));
    }

    /** Resolves the explicitly linked paid transaction, falling back to the request's idempotency prefix. */
    private Optional<KfeTransactionEntity> findSettlementTransaction(KfePaymentRequestEntity entity) {
        if (entity.getPaidTransactionId() != null) {
            return transactionRepository.findById(entity.getPaidTransactionId());
        }
        return transactionRepository.findTopByIdempotencyKeyStartingWithOrderByCreatedAtDesc(
                "payment-request:" + entity.getId() + ":");
    }

    /** Serializes multi-rail payloads to JSON via ObjectMapper (replaces manual string concat). */
    private String serializeRailsData(
            List<KfeRail> rails,
            KfeWalletAddressEntity onchainAddress,
            CustodyGateway.GeneratedLightningInvoice lightningInvoice) {
        if (rails == null || rails.isEmpty()) return null;
        List<RailDetail> details = new ArrayList<>();
        for (KfeRail r : rails) {
            if (r == KfeRail.INTERNAL) continue;
            String addr = r == KfeRail.ONCHAIN && onchainAddress != null
                    ? onchainAddress.getAddress() : null;
            String pr = r == KfeRail.LIGHTNING && lightningInvoice != null
                    ? lightningInvoice.paymentRequest() : null;
            String hash = r == KfeRail.LIGHTNING && lightningInvoice != null
                    ? lightningInvoice.paymentHash() : null;
            details.add(new RailDetail(r, addr, pr, hash));
        }
        if (details.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize rails data", e);
            return null;
        }
    }

    /** Deserializes RailsData JSON column back to RailDetail list. */
    private List<RailDetail> deserializeRailsData(KfePaymentRequestEntity entity) {
        String data = entity.getRailsData();
        if (data == null || data.isBlank()) {
            return legacyRailDetail(entity);
        }
        try {
            List<RailDetail> details = objectMapper.readValue(data, new RailDetailListTypeReference());
            if (details == null || details.isEmpty()) {
                return legacyRailDetail(entity);
            }
            return details;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse rails_data for payment request {}, falling back to legacy", entity.getId(), e);
            return legacyRailDetail(entity);
        }
    }

    /** Reconstructs one-rail details for rows written before the multi-rail JSON payload existed. */
    private List<RailDetail> legacyRailDetail(KfePaymentRequestEntity e) {
        KfeRail r = e.getRail();
        if (r == KfeRail.ONCHAIN) {
            return List.of(new RailDetail(KfeRail.ONCHAIN, e.getAddress(), null, null));
        }
        if (r == KfeRail.LIGHTNING) {
            return List.of(new RailDetail(KfeRail.LIGHTNING,
                    shortLightningAddress(e.getPaymentHash()),
                    e.getPaymentRequest(),
                    e.getPaymentHash()));
        }
        return List.of(new RailDetail(KfeRail.INTERNAL, e.getAddress(), null, null));
    }

    /** Generates a URL-safe random identifier and checks uniqueness before returning it. */
    private String generatePublicId() {
        for (int attempt = 0; attempt < 5; attempt++) {
            byte[] bytes = new byte[PUBLIC_ID_BYTES];
            RANDOM.nextBytes(bytes);
            String candidate = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toLowerCase(Locale.ROOT);
            if (paymentRequestRepository.findByPublicId(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to allocate KFE payment request public id.");
    }

    /** Trims optional user-provided text and converts blank content to null. */
    private String clean(String value) {
        return hasText(value) ? value.trim() : null;
    }

    /** Uses the wallet's direct xpub first and otherwise extracts a supported xpub from its descriptor. */
    private String receivingXpub(KfeWalletEntity wallet) {
        if (wallet == null) {
            return null;
        }
        if (hasText(wallet.getXpub())) {
            return wallet.getXpub().trim();
        }
        return extractExtendedPublicKey(wallet.getDescriptor());
    }

    /** Finds the first supported extended public key in a descriptor or returns null. */
    private String extractExtendedPublicKey(String descriptor) {
        if (!hasText(descriptor)) {
            return null;
        }
        Matcher matcher = EXTENDED_PUBLIC_KEY_PATTERN.matcher(descriptor);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Checks whether a string contains any non-whitespace characters. */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Named generic token avoids an undocumented anonymous class in the production API surface. */
    private static final class RailDetailListTypeReference extends TypeReference<List<RailDetail>> {
        /** Creates the reusable type token for a JSON array of rail details. */
        private RailDetailListTypeReference() { }
    }

    /** Serializes the fixed/open-amount behavior contract, returning null if serialization fails. */
    private String serializeBehaviorContract(KfePaymentBehaviorContract contract) {
        if (contract == null) return null;
        try {
            return objectMapper.writeValueAsString(contract);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize behavior contract", e);
            return null;
        }
    }

}
