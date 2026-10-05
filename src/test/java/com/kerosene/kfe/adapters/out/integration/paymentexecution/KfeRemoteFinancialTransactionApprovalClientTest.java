package com.kerosene.kfe.adapters.out.integration.paymentexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.adapters.out.integration.WorkloadIdentityTestClients;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;
import com.kerosene.common.financial.approval.PasskeyAssertion;
import com.kerosene.common.financial.approval.DeviceProof;
import com.kerosene.common.financial.approval.RecoveryApproval;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.http.HttpStatus;

class KfeRemoteFinancialTransactionApprovalClientTest {

    @Test
    void postsLocalFactorApprovalToAuthServer() throws Exception {
        KfeRemoteFinancialTransactionApprovalClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        server.expect(requestTo("http://server.test/internal/kfe/transaction-approval/local-factor"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-KFE-Internal-Secret", "credential"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "userId":42,
                          "deviceRef":"device",
                          "factor":{
                            "deviceId":"device",
                            "proof":"proof",
                            "challengeNonce":"challenge"
                          }
                        }
                        """))
                .andRespond(withSuccess());

        client.approveLocalFactor(42L, "device", deviceProof());

        server.verify();
    }

    @Test
    void postsWalletOutboundApprovalToAuthServer() throws Exception {
        KfeRemoteFinancialTransactionApprovalClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        server.expect(requestTo("http://server.test/internal/kfe/transaction-approval/wallet-outbound"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-KFE-Internal-Secret", "credential"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "actorUserId":41,
                          "ownerUserId":42,
                          "passkeyAssertion":{
                            "credentialId":"credential",
                            "clientDataJson":"client-data",
                            "authenticatorData":"authenticator-data",
                            "signature":"signature",
                            "userHandle":"42"
                          },
                          "recoveryApproval":{
                            "proof":"recovery-proof",
                            "challengeNonce":"challenge"
                          },
                          "deviceProof":{
                            "deviceId":"device",
                            "proof":"proof",
                            "challengeNonce":"challenge"
                          }
                        }
                        """))
                .andRespond(withSuccess());

        client.approveWalletOutbound(41L, 42L, passkey(), recoveryApproval(), deviceProof());

        server.verify();
    }

    @Test
    void rejectsMissingInternalCredentialBeforeCallingAuthServer() {
        KfeRemoteFinancialTransactionApprovalClient client = client("");

        assertThrows(IllegalStateException.class,
                () -> client.approveLocalFactor(42L, "device", deviceProof()));
    }

    @Test
    void mapsRemoteAuthChallengeToStructuredPlatformException() throws Exception {
        KfeRemoteFinancialTransactionApprovalClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        server.expect(requestTo("http://server.test/internal/kfe/transaction-approval/custody-transfer"))
                .andRespond(withStatus(HttpStatus.PRECONDITION_REQUIRED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "success": false,
                                  "message": "PASSKEY_CHALLENGE_REQUIRED:abcdef",
                                  "errorCode": "AUTH_012",
                                  "data": {"challenge": "abcdef"}
                                }
                                """));

        StructuredPlatformException exception = assertThrows(
                StructuredPlatformException.class,
                () -> client.approveCustodyTransfer(
                        42L,
                        passkey()));

        assertEquals(HttpStatus.PRECONDITION_REQUIRED, exception.getStatus());
        assertEquals(ErrorCodes.AUTH_PASSKEY_CHALLENGE, exception.getErrorCode());
        assertEquals("PASSKEY_CHALLENGE_REQUIRED:abcdef", exception.getMessage());
        server.verify();
    }

    private static PasskeyAssertion passkey() {
        return new PasskeyAssertion(
                "credential", "client-data", "authenticator-data", "signature", "42");
    }

    private static DeviceProof deviceProof() {
        return new DeviceProof(
                "device", "proof", "challenge", Instant.parse("2026-07-27T12:00:00Z"));
    }

    private static RecoveryApproval recoveryApproval() {
        return new RecoveryApproval(
                "recovery-proof", "challenge", Instant.parse("2026-07-27T12:00:00Z"));
    }

    private KfeRemoteFinancialTransactionApprovalClient client(String credential) {
        return new KfeRemoteFinancialTransactionApprovalClient(
                WorkloadIdentityTestClients.legacy(credential),
                new ObjectMapper(),
                "http://server.test",
                100,
                100);
    }

    private RestTemplate restTemplate(KfeRemoteFinancialTransactionApprovalClient client) throws Exception {
        Field field = KfeRemoteFinancialTransactionApprovalClient.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        return (RestTemplate) field.get(client);
    }
}
