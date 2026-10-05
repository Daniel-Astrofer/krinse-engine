package com.kerosene.kfe.adapters.out.integration.directory;

import com.kerosene.kfe.adapters.out.integration.WorkloadIdentityTestClients;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KfeRemoteFinancialUserDirectoryClientTest {

    @Test
    void resolvesNormalizedUsernameFromCore() throws Exception {
        KfeRemoteFinancialUserDirectoryClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        server.expect(requestTo("http://server.test/internal/kfe/user-directory/lookup"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-KFE-Internal-Secret", "credential"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"username\":\"alice\",\"userId\":null}"))
                .andRespond(withSuccess("""
                        {
                          "success": true,
                          "message": "User resolved.",
                          "data": {"id":42,"username":"alice","active":true}
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.findByUsername(" Alice ");

        assertTrue(result.isPresent());
        assertEquals(42L, result.get().id());
        assertEquals("alice", result.get().username());
        assertTrue(result.get().active());
        server.verify();
    }

    @Test
    void resolvesByUserId() throws Exception {
        KfeRemoteFinancialUserDirectoryClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        server.expect(requestTo("http://server.test/internal/kfe/user-directory/lookup"))
                .andExpect(content().json("{\"username\":null,\"userId\":42}"))
                .andRespond(withSuccess("""
                        {
                          "success": true,
                          "data": {"id":42,"username":"alice","active":false}
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.findById(42L);

        assertTrue(result.isPresent());
        assertEquals(false, result.get().active());
        server.verify();
    }

    @Test
    void mapsCoreNotFoundToEmptyOptional() throws Exception {
        KfeRemoteFinancialUserDirectoryClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        server.expect(requestTo("http://server.test/internal/kfe/user-directory/lookup"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertTrue(client.findByUsername("missing").isEmpty());
        server.verify();
    }

    @Test
    void rejectsMissingInternalCredentialBeforeCallingCore() {
        KfeRemoteFinancialUserDirectoryClient client = client("");

        assertThrows(IllegalStateException.class, () -> client.findByUsername("alice"));
    }

    @Test
    void mapsRejectedCoreResponseToUnavailable() throws Exception {
        KfeRemoteFinancialUserDirectoryClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        server.expect(requestTo("http://server.test/internal/kfe/user-directory/lookup"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> client.findByUsername("alice"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
        server.verify();
    }

    @Test
    void rejectsMalformedSuccessfulCoreResponse() throws Exception {
        KfeRemoteFinancialUserDirectoryClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        server.expect(requestTo("http://server.test/internal/kfe/user-directory/lookup"))
                .andRespond(withSuccess("""
                        {
                          "success": true,
                          "data": {"id":null,"username":"alice","active":true}
                        }
                        """, MediaType.APPLICATION_JSON));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> client.findByUsername("alice"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
        server.verify();
    }

    private KfeRemoteFinancialUserDirectoryClient client(String credential) {
        return new KfeRemoteFinancialUserDirectoryClient(
                WorkloadIdentityTestClients.legacy(credential),
                "http://server.test/",
                100,
                100);
    }

    private RestTemplate restTemplate(KfeRemoteFinancialUserDirectoryClient client) throws Exception {
        Field field = KfeRemoteFinancialUserDirectoryClient.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        return (RestTemplate) field.get(client);
    }
}
