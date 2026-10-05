package com.kerosene.kfe.adapters.out.integration.paymentexecution;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;
import com.kerosene.common.financial.approval.FinancialPaymentApprovalV1;
import com.kerosene.common.financial.approval.FinancialPaymentApprovalV1.Challenge;
import com.kerosene.common.financial.approval.FinancialPaymentApprovalV1.Context;
import com.kerosene.common.financial.approval.FinancialPaymentApprovalV1.Request;
import com.kerosene.common.financial.approval.FinancialPaymentApprovalV1.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** HTTP serialization and failure mapping; no real Core, network or financial side effects. */
class KfeRemotePaymentApprovalV1ClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENDPOINT = "http://server.test" + FinancialPaymentApprovalV1.PATH;
    private static final String SECRET = "test-internal-secret";

    @Test
    void postsTheCompleteVersionedRequestOnlyToTheFinancialEndpoint() throws Exception {
        var client = client(SECRET, JSON);
        var server = server(client);
        var response = challengeResponse();
        server.expect(requestTo(ENDPOINT)).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-KFE-Internal-Secret", SECRET))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(JSON.writeValueAsString(request()), true))
                .andRespond(withSuccess(JSON.writeValueAsString(response), MediaType.APPLICATION_JSON));

        assertThat(client.approve(request())).isEqualTo(response);

        server.verify();
    }

    @Test
    void emitsLowerCamelCaseAndExplicitNullFieldsWithoutMutatingTheGlobalMapper() throws Exception {
        var global = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var client = client(SECRET, global);
        var server = server(client);
        server.expect(requestTo(ENDPOINT)).andExpect(content().json(JSON.writeValueAsString(request()), true))
                .andRespond(withSuccess(JSON.writeValueAsString(challengeResponse()), MediaType.APPLICATION_JSON));

        client.approve(request());

        assertThat(global.getPropertyNamingStrategy()).isSameAs(PropertyNamingStrategies.SNAKE_CASE);
        assertThat(global.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
        assertThat(global.getSerializationConfig().getDefaultPropertyInclusion().getValueInclusion()).isEqualTo(JsonInclude.Include.NON_NULL);
        server.verify();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void missingInternalSecretFailsBeforeAnyNetworkRequest(String secret) throws Exception {
        var client = client(secret, JSON);
        var server = server(client);

        assertError(() -> client.approve(request()), HttpStatus.SERVICE_UNAVAILABLE, "KFE_PAYMENT_APPROVAL_UNAVAILABLE");

        server.verify();
    }

    @Test
    void nullRequestFailsBeforeAnyNetworkRequest() throws Exception {
        var client = client(SECRET, JSON);
        var server = server(client);

        assertError(() -> client.approve(null), HttpStatus.BAD_REQUEST, "KFE_PAYMENT_PROOF_INVALID");

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "whitespace", "null", "array", "broken", "unknown", "duplicate", "string-version", "fractional-version", "null-version", "missing-challenge", "status", "trailing", "wrapped", "oversized"})
    void malformedOrAmbiguousSuccessfulBodyCannotBeAnApproval(String mutation) throws Exception {
        String valid = JSON.writeValueAsString(approved());
        String invalid = switch (mutation) {
            case "empty" -> "";
            case "whitespace" -> " \n";
            case "null" -> "null";
            case "array" -> "[" + valid + "]";
            case "broken" -> "{SECRET-REMOTE-BODY";
            case "unknown" -> valid.substring(0, valid.length() - 1) + ",\"unexpected\":\"SECRET-REMOTE-BODY\"}";
            case "duplicate" -> valid.replace("\"version\":1", "\"version\":1,\"version\":1");
            case "string-version" -> valid.replace("\"version\":1", "\"version\":\"1\"");
            case "fractional-version" -> valid.replace("\"version\":1", "\"version\":1.5");
            case "null-version" -> valid.replace("\"version\":1", "\"version\":null");
            case "missing-challenge" -> valid.replace(",\"challenge\":null", "");
            case "status" -> valid.replace("APPROVED", "SUCCESS");
            case "trailing" -> valid + " {}";
            case "wrapped" -> "{\"success\":true,\"data\":" + valid + "}";
            default -> "SECRET-REMOTE-BODY".repeat(2000);
        };
        var client = client(SECRET, JSON);
        var server = server(client);
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(invalid, MediaType.APPLICATION_JSON));

        assertError(() -> client.approve(request()), HttpStatus.BAD_GATEWAY, "KFE_PAYMENT_APPROVAL_INVALID");

        server.verify();
    }

    @Test
    void noContentSuccessIsNotAuthorization() throws Exception {
        var client = client(SECRET, JSON);
        var server = server(client);
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertError(() -> client.approve(request()), HttpStatus.BAD_GATEWAY, "KFE_PAYMENT_APPROVAL_INVALID");

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 409, 422, 428, 429, 500, 503})
    void retainsRemoteErrorStatusButDiscardsUntrustedMessageCodeAndData(int status) throws Exception {
        var client = client(SECRET, JSON);
        var server = server(client);
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"SECRET-REMOTE-BODY\",\"errorCode\":\"SECRET-REMOTE-CODE\",\"data\":{\"proof\":\"SECRET-PROOF\"}}"));

        var error = assertError(() -> client.approve(request()), HttpStatus.valueOf(status), "KFE_PAYMENT_APPROVAL_REJECTED");
        assertThat(error.getData()).isNull();

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json-SECRET-REMOTE-BODY", "{\"message\":\"SECRET-REMOTE-BODY\"", "null"})
    void malformedErrorBodiesNeverBecomeDiagnostics(String body) throws Exception {
        var client = client(SECRET, JSON);
        var server = server(client);
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body(body));

        var error = assertError(() -> client.approve(request()), HttpStatus.FORBIDDEN, "KFE_PAYMENT_APPROVAL_REJECTED");
        assertThat(error.getData()).isNull();

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {ErrorCodes.AUTH_APP_PIN_INVALID, ErrorCodes.AUTH_APP_PIN_LOCKED,
            ErrorCodes.AUTH_APP_PIN_NOT_CONFIGURED, ErrorCodes.AUTH_APP_PIN_DEVICE_REQUIRED})
    void knownPinErrorsExposeOnlyAnExplicitPrimitiveStatusAllowList(String code) throws Exception {
        var client = client(SECRET, JSON);
        var server = server(client);
        String response = """
                {"message":"SECRET-REMOTE-BODY","errorCode":"%s","data":{
                "enabled":true,"configured":true,"locked":true,"resettableWithTotp":false,"deviceScoped":true,
                "failedAttempts":5,"remainingAttempts":0,"maxAttempts":5,"minPinLength":4,"maxPinLength":8,
                "lockedUntil":"2026-09-15T12:01:02","pin":"SECRET-PROOF","nested":{"proof":"SECRET-PROOF"}}}
                """.formatted(code);
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON).body(response));

        var error = assertError(() -> client.approve(request()), HttpStatus.UNAUTHORIZED, code);
        assertThat(error.getData()).isEqualTo(Map.ofEntries(
                Map.entry("enabled", true), Map.entry("configured", true), Map.entry("locked", true), Map.entry("resettableWithTotp", false),
                Map.entry("deviceScoped", true), Map.entry("failedAttempts", 5), Map.entry("remainingAttempts", 0),
                Map.entry("maxAttempts", 5), Map.entry("minPinLength", 4), Map.entry("maxPinLength", 8), Map.entry("lockedUntil", "2026-09-15T12:01:02")));

        server.verify();
    }

    @Test
    void malformedPinStatusFieldsCannotSmuggleStringsNestedObjectsOrCoercions() throws Exception {
        var client = client(SECRET, JSON);
        var server = server(client);
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"errorCode":"%s","data":{"enabled":"true","configured":{},"locked":null,"failedAttempts":-1,
                        "remainingAttempts":"0","maxAttempts":9999999999999,"minPinLength":4.5,"maxPinLength":{},
                        "lockedUntil":"SECRET-REMOTE-BODY","signature":"SECRET-PROOF"}}
                        """.formatted(ErrorCodes.AUTH_APP_PIN_INVALID)));

        var error = assertError(() -> client.approve(request()), HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_APP_PIN_INVALID);
        assertThat(error.getData()).isEqualTo(Map.of());

        server.verify();
    }

    @Test
    void unknownRemoteHttpStatusMapsToBadGatewayWithoutLeakingItsBody() throws Exception {
        var client = client(SECRET, JSON);
        var server = server(client);
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatusCode.valueOf(499)).body("SECRET-REMOTE-BODY"));

        assertError(() -> client.approve(request()), HttpStatus.BAD_GATEWAY, "KFE_PAYMENT_APPROVAL_REJECTED");

        server.verify();
    }

    @Test
    void networkFailureIsUnavailableWithoutLeakingUrlCredentialsOrCause() throws Exception {
        var client = client(SECRET, JSON);
        var server = server(client);
        server.expect(requestTo(ENDPOINT)).andRespond(request -> { throw new IOException("SECRET-REMOTE-BODY " + SECRET); });

        assertError(() -> client.approve(request()), HttpStatus.SERVICE_UNAVAILABLE, "KFE_PAYMENT_APPROVAL_UNAVAILABLE");

        server.verify();
    }

    private static StructuredPlatformException assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            HttpStatus status, String code) {
        var error = catchThrowableOfType(action, StructuredPlatformException.class);
        assertThat(error).isNotNull();
        assertThat(error.getStatus()).isEqualTo(status);
        assertThat(error.getErrorCode()).isEqualTo(code);
        assertThat(error.getMessage()).doesNotContain(SECRET, "SECRET-REMOTE-BODY", "SECRET-REMOTE-CODE", "SECRET-PROOF", " pin ", ENDPOINT);
        assertThat(error.getCause()).isNull();
        return error;
    }

    private static KfeRemotePaymentApprovalV1Client client(String secret, ObjectMapper mapper) {
        return new KfeRemotePaymentApprovalV1Client(new RestTemplateBuilder(), mapper, "http://server.test///", secret, 100, 100);
    }

    private static MockRestServiceServer server(KfeRemotePaymentApprovalV1Client client) throws Exception {
        Field field = KfeRemotePaymentApprovalV1Client.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        return MockRestServiceServer.createServer((RestTemplate) field.get(client));
    }

    private static Request request() {
        var context = new Context(41L, "device-ref", " key ", "ONCHAIN", "OUTBOUND", "source", null,
                10000, 100, "address", " memo | 漢 ", null, 27L, 3, "quote");
        return new Request(1, context, " pin ", null, null, null);
    }

    private static Response challengeResponse() {
        long now = Instant.now().getEpochSecond();
        String binding = FinancialPaymentApprovalV1.bindingHash(request().context());
        var challenge = new Challenge(1, FinancialPaymentApprovalV1.PURPOSE, "challenge-id", "a".repeat(64), binding,
                "alice", "auth-test", now - 1, now + 90, "Ed25519", FinancialPaymentApprovalV1.CANONICALIZATION);
        return new Response(1, "CHALLENGE", binding, null, challenge.expiresAtEpochSeconds(), challenge);
    }

    private static Response approved() {
        return new Response(1, "APPROVED", FinancialPaymentApprovalV1.bindingHash(request().context()), "challenge-id",
                Instant.now().getEpochSecond() + 90, null);
    }
}
