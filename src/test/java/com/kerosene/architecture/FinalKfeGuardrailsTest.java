package com.kerosene.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Cross-context source guards for the final KFE-only and zero-trust boundary. */
class FinalKfeGuardrailsTest {
    private static final Path ROOT = Path.of("src/main/java/com/kerosene/kfe");
    private static final List<String> FORBIDDEN_PRODUCTION_MARKERS = List.of(
            "beta-pass",
            "allow-simulated-balances",
            "kfe.security.enabled",
            "kfe.legacy-financial.enabled",
            "source.kfe",
            "HashiCorp Raft",
            "mpc-sidecar");
    private static final List<String> FORBIDDEN_PATHS = List.of(
            "application/settlement/BinarySettlementGate.java",
            "application/settlement/SettlementGateCommand.java",
            "application/settlement/SettlementFlag.java",
            "application/transaction/KfeTransactionCancellationService.java",
            "application/transaction/KfeTransactionStateMachine.java",
            "service/KfeTransactionCancellationService.java",
            "paymentexecution/application/port/out/PaymentCancellationPort.java",
            "paymentexecution/adapters/out/legacy/LegacyPaymentCancellationAdapter.java");
    private static final List<String> FRAMEWORK_IMPORTS = List.of(
            "org.springframework.", "jakarta.persistence.", "com.fasterxml.jackson.", "com.kerosene.common.");

    @Test
    void allPureContextsKeepFrameworkAndOuterAdaptersOut() throws IOException {
        for (String context : List.of("pricing", "paymentexecution", "ledger", "wallet", "liquidity")) {
            assertNoImports(ROOT.resolve(context + "/domain"), FRAMEWORK_IMPORTS);
            assertNoImports(ROOT.resolve(context + "/application"), FRAMEWORK_IMPORTS);
            assertNoForeignOuterImports(ROOT.resolve(context + "/domain"));
            assertNoForeignOuterImports(ROOT.resolve(context + "/application"));
        }
    }

    @Test
    void noProhibitedProductionMarkerOrLegacyPathCanReturn() throws IOException {
        for (String marker : FORBIDDEN_PRODUCTION_MARKERS) {
            assertThat(productionSource()).as("prohibited production marker: %s", marker)
                    .doesNotContain(marker);
        }
        for (String relative : FORBIDDEN_PATHS) {
            assertThat(ROOT.resolve(relative)).as("removed KFE path returned: %s", relative).doesNotExist();
        }
        try (var paths = Files.walk(Path.of("src"))) {
            assertThat(paths.filter(path -> path.getFileName().toString().equals("package-info.java")).toList())
                    .isEmpty();
        }
    }

    @Test
    void runtimeSecurityCannotBeDisabledByAProperty() throws IOException {
        String security = Files.readString(ROOT.resolve("bootstrap/security/KfeStandaloneSecurityConfiguration.java"));
        String filter = Files.readString(ROOT.resolve("bootstrap/security/KfeJwtAuthenticationFilter.java"));
        assertThat(security).doesNotContain("kfe.security.enabled", "securityEnabled");
        assertThat(filter).doesNotContain("securityEnabled", "if (!securityEnabled)");
        String defaults = Files.readString(Path.of("src/main/resources/kfe-service-defaults.properties"));
        assertThat(defaults).contains("kfe.auth.revocation.required=${KFE_AUTH_REVOCATION_REQUIRED:true}")
                .doesNotContain("kfe.security.enabled");
    }

    @Test
    void productionGateRequiresExplicitIdentityAndRevocationConfiguration() throws IOException {
        String gate = Files.readString(ROOT.resolve("bootstrap/config/financial/KfeProductionGateConfig.java"));
        assertThat(gate).contains("JWT issuer", "JWT audience", "JWT revocation must be enabled and required",
                "kfe.internal.shared-secret must be a dedicated random secret");
        String policy = Files.readString(ROOT.resolve("paymentexecution/domain/model/SettlementGatePolicy.java"));
        assertThat(policy).contains("must be exactly 'enforce'", "threshold > constitutionMemberCount")
                .doesNotContain("beta-pass", "Math.min");
        assertThat(gate).contains("checkJwtKeyRotation", "previous secret rotation window has expired");
    }

    @Test
    void workloadTransportRequiresIdentityAndOperationAllowList() throws IOException {
        String authorizer = Files.readString(ROOT.resolve("messaging/application/WorkloadOperationAuthorizer.java"));
        String transport = Files.readString(ROOT.resolve("adapters/out/integration/messaging/KfeAuthenticatedMessageTransport.java"));
        assertThat(authorizer).contains("Missing or unknown policy is deliberately denied", "requireAllowed");
        assertThat(transport).contains("operationAuthorizer.requireAllowed(workload, message.type())");
    }

    @Test
    void inboundMessageIngressAuthenticatesBeforeDurableAcceptance() throws IOException {
        String ingress = Files.readString(ROOT.resolve("messaging/adapters/in/http/KfeAuthenticatedMessageIngressController.java"));
        assertThat(ingress).contains(
                "MessageDigest.isEqual",
                "X-KFE-Workload-Id",
                "X-KFE-Message-Signature",
                "ReceiveMessageUseCase",
                "HttpStatus.ACCEPTED");
    }

    @Test
    void requiredRevocationCannotBeDisabledOrSkipped() throws IOException {
        String verifier = Files.readString(ROOT.resolve("bootstrap/security/KfeJwtVerifier.java"));
        assertThat(verifier).contains("if (revocationRequired)", "sessionId")
                .contains("previousSecretExpiresAt");
    }

    @Test
    void outpointProbeIsFailClosedOnInfrastructureFailure() throws IOException {
        String source = Files.readString(ROOT.resolve("adapters/out/rail/onchain/BlockchainClient.java"));
        assertThat(source).contains("return false;")
                .doesNotContain("return true; // fail open");
    }

    @Test
    void documentedCoreFinancialRoutesHaveAnOwningInboundAdapter() throws IOException {
        assertThat(Files.readString(ROOT.resolve("paymentexecution/adapters/in/http/SubmitPaymentController.java")))
                .contains("@RequestMapping(\"/kfe/transactions\")", "@PostMapping");
        assertThat(Files.readString(ROOT.resolve("paymentexecution/adapters/in/http/PaymentExecutionController.java")))
                .contains("@RequestMapping(\"/kfe/transactions\")", "@GetMapping(\"/{transactionId}\")", "@PostMapping(\"/{transactionId}/cancel\")");
        assertThat(Files.readString(ROOT.resolve("pricing/adapters/in/http/TransactionQuoteController.java")))
                .contains("@PostMapping(\"/quote\")");
    }

    private static String productionSource() throws IOException {
        StringBuilder source = new StringBuilder();
        try (var paths = Files.walk(Path.of("src/main"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (path.toString().endsWith(".java") || path.toString().endsWith(".properties")) {
                    source.append(Files.readString(path)).append('\n');
                }
            }
        }
        return source.toString();
    }

    private static void assertNoImports(Path root, List<String> forbidden) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(path)) {
                    if (!line.stripLeading().startsWith("import ")) {
                        continue;
                    }
                    for (String prefix : forbidden) {
                        assertThat(line).as("forbidden import in %s", path).doesNotContain(prefix);
                    }
                }
            }
        }
    }

    private static void assertNoForeignOuterImports(Path root) throws IOException {
        Pattern forbidden = Pattern.compile(
                "com\\.kerosene\\.kfe\\.(?!" + root.getName(root.getNameCount() - 2) + "\\.)"
                        + "(?:[\\w]+\\.)*(?:adapters|config|bootstrap|controller|dto|integration|model|rail|repository|runtime|service)\\.");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(path)) {
                    if (line.stripLeading().startsWith("import ")) {
                        assertThat(forbidden.matcher(line).find())
                                .as("foreign outer dependency in %s: %s", path, line).isFalse();
                    }
                }
            }
        }
    }
}
