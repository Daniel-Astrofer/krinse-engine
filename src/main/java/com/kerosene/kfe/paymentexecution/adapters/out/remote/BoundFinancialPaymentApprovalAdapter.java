package com.kerosene.kfe.paymentexecution.adapters.out.remote;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.kerosene.common.exception.StructuredPlatformException;
import com.kerosene.common.financial.approval.FinancialPaymentApprovalPort;
import com.kerosene.common.financial.approval.FinancialPaymentApprovalV1;
import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentApprovalPort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Converts the canonical application command to the versioned request-bound approval protocol. */
@Component
public final class BoundFinancialPaymentApprovalAdapter implements PaymentApprovalPort {
    private static final Set<String> PAYLOAD_FIELDS = Set.of("bindingHash", "challenge", "challengeId",
            "counter", "credentialId", "deviceInstallId", "issuedAtEpochSeconds", "onionServiceId",
            "type", "username", "version");
    private final FinancialPaymentApprovalPort delegate;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    public BoundFinancialPaymentApprovalAdapter(FinancialPaymentApprovalPort delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public void approve(SubmitPaymentCommand command) {
        final FinancialPaymentApprovalV1.Request request;
        final JsonNode signed;
        try {
            var context = new FinancialPaymentApprovalV1.Context(command.userId(), command.deviceHash(),
                    command.idempotencyKey().value(), command.rail().name(), command.direction().name(),
                    command.sourceWalletId() == null ? null : command.sourceWalletId().toString(),
                    command.destinationWalletId() == null ? null : command.destinationWalletId().toString(),
                    command.amountSats(), command.networkFeeSats(), command.externalReference(), command.memo(),
                    command.paymentRequestPublicId(), command.feeRateSatPerVbyte(), command.feeTargetBlocks(), command.quoteId());
            String assertion = command.passkeyAssertionJson();
            FinancialPaymentApprovalV1.Proof proof = null;
            if (assertion != null && !assertion.isBlank()) {
                if (assertion.length() > 16384) { throw new IllegalArgumentException(); }
                proof = mapper.readValue(assertion, FinancialPaymentApprovalV1.Proof.class);
                if (proof == null) { throw new IllegalArgumentException(); }
            }
            signed = proof == null ? null : readSignedPayload(proof, context);
            request = new FinancialPaymentApprovalV1.Request(1, context, command.appPin(), command.totpCode(),
                    command.confirmationPassphrase(), proof);
        } catch (Exception rejected) {
            // Do not retain parser exceptions: they can contain PINs, proofs or payment details.
            throw new StructuredPlatformException("Prova de autorizacao financeira invalida.", HttpStatus.BAD_REQUEST,
                    "KFE_PAYMENT_PROOF_INVALID", null);
        }

        var response = delegate.approve(request);
        long now = Instant.now().getEpochSecond();
        if (response == null || !FinancialPaymentApprovalV1.bindingHash(request.context()).equals(response.bindingHash())
                || response.expiresAtEpochSeconds() <= now || response.expiresAtEpochSeconds() - now > 120) {
            throw invalidApproval();
        }
        if ("CHALLENGE".equals(response.status())) {
            if (response.challenge().issuedAtEpochSeconds() > now + 30) { throw invalidApproval(); }
            throw new StructuredPlatformException("Assinatura financeira do dispositivo obrigatoria.",
                    HttpStatus.PRECONDITION_REQUIRED, "KFE_PAYMENT_APPROVAL_REQUIRED",
                    Map.of("action", "ASSERT_FINANCIAL_DEVICE_KEY", "financialChallenge", response.challenge()));
        }
        if (signed == null || !response.approvalId().equals(signed.path("challengeId").textValue())
                || response.expiresAtEpochSeconds() <= signed.path("issuedAtEpochSeconds").longValue()
                || response.expiresAtEpochSeconds() - signed.path("issuedAtEpochSeconds").longValue() > 120) {
            throw invalidApproval();
        }
    }

    private JsonNode readSignedPayload(FinancialPaymentApprovalV1.Proof proof, FinancialPaymentApprovalV1.Context context)
            throws Exception {
        JsonNode signed = mapper.readTree(proof.signedPayload());
        Set<String> fields = new HashSet<>();
        if (signed == null || !signed.isObject()) { throw new IllegalArgumentException(); }
        signed.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(PAYLOAD_FIELDS)
                || !signed.path("version").isIntegralNumber() || !signed.path("version").canConvertToInt()
                || signed.path("version").intValue() != 1
                || !FinancialPaymentApprovalV1.PURPOSE.equals(signed.path("type").textValue())
                || !FinancialPaymentApprovalV1.bindingHash(context).equals(signed.path("bindingHash").textValue())
                || !proof.credentialId().equals(signed.path("credentialId").textValue())
                || !proof.deviceInstallId().equals(signed.path("deviceInstallId").textValue())
                || !textMatches(signed.path("challengeId"), "[A-Za-z0-9-]{1,128}")
                || !textMatches(signed.path("challenge"), "[0-9a-f]{64}")
                || !validText(signed.path("username")) || !validText(signed.path("onionServiceId"))
                || !positiveSafeInteger(signed.path("counter")) || !positiveSafeInteger(signed.path("issuedAtEpochSeconds"))
                || signed.path("issuedAtEpochSeconds").longValue() > Instant.now().getEpochSecond() + 30
                || !proof.signature().matches("[A-Za-z0-9_-]{86}")) {
            throw new IllegalArgumentException();
        }
        return signed;
    }

    private static boolean textMatches(JsonNode node, String pattern) {
        return node.isTextual() && node.textValue().matches(pattern);
    }
    private static boolean validText(JsonNode node) {
        return node.isTextual() && !node.textValue().isBlank() && node.textValue().length() <= 255;
    }
    private static boolean positiveSafeInteger(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() > 0
                && node.longValue() <= 9_007_199_254_740_991L;
    }
    private static StructuredPlatformException invalidApproval() {
        return new StructuredPlatformException("Resposta de autorizacao financeira invalida.", HttpStatus.BAD_GATEWAY,
                "KFE_PAYMENT_APPROVAL_INVALID", null);
    }
}
