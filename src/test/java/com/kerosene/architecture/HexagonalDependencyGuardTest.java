package com.kerosene.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Source-level guard that keeps domain and migrated application slices framework-independent. */
class HexagonalDependencyGuardTest {

    private static final Path MAIN_SOURCE = Path.of("src/main/java/com/kerosene/kfe");
    private static final String LEGACY_CONTROLLER_PACKAGE = "com.kerosene.kfe." + "controller.";
    private static final Pattern OUTER_CONTEXT_IMPORT = Pattern.compile(
            "com\\.kerosene\\.kfe\\.(?:[\\w]+\\.)*(?:adapters|config|bootstrap)\\.");
    private static final Pattern APPLICATION_IMPORT = Pattern.compile(
            "com\\.kerosene\\.kfe\\.(?:[\\w]+\\.)*application\\.");
    private static final List<String> DOMAIN_FORBIDDEN_IMPORTS = List.of(
            "org.springframework.",
            "jakarta.persistence.",
            "com.fasterxml.jackson.",
            "com.kerosene.common.",
            "com.kerosene.kfe.adapters.",
            LEGACY_CONTROLLER_PACKAGE,
            "com.kerosene.kfe.adapters.in.http.dto.",
            "com.kerosene.kfe.adapters.out.integration.",
            "com.kerosene.kfe.adapters.out.persistence.model.",
            "com.kerosene.kfe.adapters.out.rail.",
            "com.kerosene.kfe.adapters.out.persistence.repository.",
            "com.kerosene.kfe.runtime.",
            "com.kerosene.kfe.bootstrap.",
            "com.kerosene.kfe.legacy.application.service.");
    private static final List<String> APPLICATION_FORBIDDEN_IMPORTS = List.of(
            "org.springframework.",
            "jakarta.persistence.",
            "com.fasterxml.jackson.",
            "com.kerosene.common.",
            "com.kerosene.kfe.adapters.",
            LEGACY_CONTROLLER_PACKAGE,
            "com.kerosene.kfe.dto.",
            "com.kerosene.kfe.integration.",
            "com.kerosene.kfe.model.",
            "com.kerosene.kfe.rail.",
            "com.kerosene.kfe.repository.",
            "com.kerosene.kfe.runtime.",
            "com.kerosene.kfe.bootstrap.",
            "com.kerosene.kfe.service.");

    @Test
    void domainDoesNotDependOnFrameworkOrAdapters() throws IOException {
        var domain = javaFilesInDirectoriesNamed("domain");
        assertNoForbiddenImports(domain, DOMAIN_FORBIDDEN_IMPORTS);
        assertNoImportsMatching(domain, OUTER_CONTEXT_IMPORT, APPLICATION_IMPORT);
    }

    @Test
    void migratedApplicationsDependOnlyOnPortsAndDomain() throws IOException {
        for (String context : List.of("pricing", "paymentexecution", "paymentrequest", "wallet",
                "ledger", "liquidity", "messaging", "audit", "bootstrap")) {
            Path application = MAIN_SOURCE.resolve(context + "/application");
            if (Files.isDirectory(application)) {
                var files = javaFilesUnder(application);
                assertNoForbiddenImports(files, APPLICATION_FORBIDDEN_IMPORTS);
                assertNoImportsMatching(files, OUTER_CONTEXT_IMPORT);
            }
        }
    }

    @Test
    void contextualServicesCannotReturnToTheGenericLegacyPackage() throws IOException {
        assertThat(MAIN_SOURCE.resolve("legacy")).doesNotExist();
        assertThat(Path.of("src/test/java/com/kerosene/kfe/legacy")).doesNotExist();
        String removedPackage = "com.kerosene.kfe." + "legacy.";
        for (Path file : javaFilesUnder(Path.of("src"))) {
            for (String line : Files.readAllLines(file)) {
                String declaration = line.stripLeading();
                if (declaration.startsWith("package ") || declaration.startsWith("import ")) {
                    assertThat(declaration).as("generic legacy dependency in %s", file)
                            .doesNotContain(removedPackage);
                }
            }
        }
    }

    @Test
    void inboundAdaptersDoNotReintroduceCompatibilityPackages() throws IOException {
        try (var paths = Files.walk(Path.of("src"))) {
            assertThat(paths.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().equals("compatibility")))
                    .isEmpty();
        }
        for (Path file : javaFilesUnder(Path.of("src"))) {
            for (String line : Files.readAllLines(file)) {
                String declaration = line.stripLeading();
                if (declaration.startsWith("package ") || declaration.startsWith("import ")) {
                    assertThat(declaration).as("removed adapter layer referenced in %s", file)
                            .doesNotContain(".adapters.in." + "compatibility.");
                }
            }
        }
    }

    @Test
    void genericRootPackagesDoNotReturn() {
        for (String root : List.of("application", "domain", "exception", "runtime", "time", "webhook")) {
            assertThat(MAIN_SOURCE.resolve(root)).as("generic production root returned: %s", root).doesNotExist();
            assertThat(Path.of("src/test/java/com/kerosene/kfe").resolve(root))
                    .as("generic test root returned: %s", root)
                    .doesNotExist();
        }
    }

    @Test
    void walletDomainAndApplicationStayIndependentFromJPAAndBitcoinAdapters() throws IOException {
        assertNoForbiddenImports(
                javaFilesUnder(MAIN_SOURCE.resolve("wallet/domain")),
                DOMAIN_FORBIDDEN_IMPORTS);
        assertNoImportsMatching(
                javaFilesUnder(MAIN_SOURCE.resolve("wallet/domain")),
                OUTER_CONTEXT_IMPORT,
                APPLICATION_IMPORT);
        assertNoForbiddenImports(
                javaFilesUnder(MAIN_SOURCE.resolve("wallet/application")),
                APPLICATION_FORBIDDEN_IMPORTS);
        assertNoImportsMatching(
                javaFilesUnder(MAIN_SOURCE.resolve("wallet/application")),
                OUTER_CONTEXT_IMPORT);
    }

    @Test
    void submitAndCancellationUseFinancialPorts() throws IOException {
        assertNoForbiddenImports(
                List.of(
                        MAIN_SOURCE.resolve("paymentexecution/adapters/in/KfeSubmitTransactionUseCase.java"),
                        MAIN_SOURCE.resolve("paymentexecution/application/usecase/CancelPaymentService.java")),
                List.of(
                        "com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService",
                        "com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService",
                        "com.kerosene.kfe.liquidity.adapters.out.persistence.KfeLightningLiquidityService",
                        "com.kerosene.kfe.ledger.adapters.out.persistence.settlement.KfeFeeSettlementService",
                        "com.kerosene.kfe.ledger.adapters.out.persistence.KfeBalanceMovementRecorder",
                        "com.kerosene.kfe.application.transaction.KfeTransactionStatementRecorder"));
        for (String relative : List.of(
                "paymentexecution/adapters/in/KfeSubmitTransactionUseCase.java",
                "paymentexecution/application/usecase/CancelPaymentService.java")) {
            String source = Files.readString(MAIN_SOURCE.resolve(relative));
            assertThat(source).doesNotContain(
                    "KfeBalanceService", "KfeStatementService",
                    "KfeLightningLiquidityService", "KfeFeeSettlementService",
                    "KfeBalanceMovementRecorder", "KfeTransactionStatementRecorder");
        }
    }

    @Test
    void cancellationUsesInvoicePortInsteadOfRailGateway() throws IOException {
        String source = Files.readString(MAIN_SOURCE.resolve(
                "paymentexecution/application/usecase/CancelPaymentService.java"));
        assertThat(source).contains("PaymentInvoiceCancellationPort")
                .doesNotContain("LightningInvoiceGateway", "CustodyGateway", "com.kerosene.kfe.adapters.out.rail.");
    }

    @Test
    void cancellationCoreDelegatesFinancialEffectsAndUsesPortsForRequestWrites() throws IOException {
        String source = Files.readString(MAIN_SOURCE.resolve(
                "paymentexecution/application/usecase/CancelPaymentService.java"));
        assertThat(source).contains("CancelPaymentEffectsService", "PaymentRequestCancellationStatePort",
                "PaymentRequestCancellationAuditPort", "PaymentCancellationNotificationPort");
        assertThat(source).doesNotContain(
                "PaymentLedgerPort", "PaymentLiquidityPort", "PaymentStatementPort",
                "RecordPaymentStatementCommand", ".releaseReserved(", ".setFailureCode(",
                ".setFailureMessage(", "KFE_TRANSACTION_CANCELLED", "KfeResponseMapper",
                "KfeTransactionEntity", "KfePaymentRequestEntity", "@Transactional");
    }

    @Test
    void submitUsesThePricingInputPortInsteadOfMarketOrPricingServices() throws IOException {
        String source = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/in/KfeSubmitTransactionUseCase.java"));
        assertThat(source).contains("PreparePaymentSubmissionUseCase")
                .doesNotContain("KfePricingService", "KfeNetworkFeeEstimateService", "FinancialTickerPort",
                        "PricingCalculator", "PricingPolicyPort", "convertSnapshot", "resolveNetworkFeeReserve");
    }

    @Test
    void submitUsesWalletAndGatePortsWithoutExportingJpaWallets() throws IOException {
        String source = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/in/KfeSubmitTransactionUseCase.java"));
        assertThat(source).contains("PreflightPaymentUseCase", "PreparePaymentSubmissionUseCase", "CompletePaymentSubmissionUseCase")
                .doesNotContain("KfeTransactionWalletResolver", "KfeWalletEntity", "KfeWalletRepository",
                        "BinarySettlementGate", "com.kerosene.kfe.application.settlement.");
        assertThat(MAIN_SOURCE.resolve("application/transaction/KfeTransactionWalletResolver.java")).doesNotExist();
    }

    @Test
    void settlementGateRulesAndEvaluationBelongToThePureCore() throws IOException {
        String core = Files.readString(MAIN_SOURCE.resolve("paymentexecution/application/usecase/PaymentSettlementGateService.java"));
        assertThat(core).contains("PaymentGateBalancePort", "PaymentGateSolvencyPort", "SettlementGatePolicy",
                "evaluateMpc(", "evaluateReservaMat(", "audit.record(", "telemetry.recordSettlementGate(")
                .doesNotContain("KfeBalanceEntity", "KfeBalanceRepository", "KfeQuorumGateway", "ObjectProvider", "@Transactional");
        for (String removed : List.of("application/settlement/BinarySettlementGate.java",
                "application/settlement/SettlementGateCommand.java",
                "paymentexecution/application/port/out/PaymentSettlementGatePort.java",
                "paymentexecution/adapters/out/settlement/LegacyPaymentSettlementGateAdapter.java")) {
            assertThat(MAIN_SOURCE.resolve(removed)).doesNotExist();
        }
        assertNoForbiddenImports(javaFilesUnder(MAIN_SOURCE), List.of("com.kerosene.kfe.application.settlement."));
    }

    @Test
    void submissionPreparationOwnsPricingProposalAndGateWithoutJpaOrLegacySubmitHelpers() throws IOException {
        String submit = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/in/KfeSubmitTransactionUseCase.java"));
        assertThat(submit).doesNotContain("validateQuoteAndQuorum(", "applyPricing(", "proposalHash(", "KfeHashService",
                "PaymentSettlementGateUseCase", "PreparePaymentPricingUseCase", "KFE_TX_PROPOSAL",
                "KFE_TRANSACTION_VALIDATING", "KFE_TRANSACTION_QUORUM_SYNC", "setQuorumProposalHash(", "setQuorumAckCount(");
        String core = Files.readString(MAIN_SOURCE.resolve("paymentexecution/application/usecase/PreparePaymentSubmissionService.java"));
        assertThat(core).contains("PaymentSubmissionStatePort", "PaymentWalletsUseCase", "PreparePaymentPricingUseCase",
                "PaymentSettlementGateUseCase", "PaymentProposalHashPort", "requireReadyFor(", "requireConfirmed(")
                .doesNotContain("KfeTransactionEntity", "KfeSubmitTransactionRequest", "KfeHashService", "@Transactional");
    }

    @Test
    void submissionCompletionOwnsIdempotencyProjectionAndPostCommitPublicationThroughPorts() throws IOException {
        String submit = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/in/KfeSubmitTransactionUseCase.java"));
        assertThat(submit).contains("CompletePaymentSubmissionUseCase", "CompletePaymentSubmissionCommand")
                .doesNotContain("completePublishAndRespond(", "publishDashboards(", "KfeDashboardPublisher", "idempotencyUseCase.complete(");
        String core = Files.readString(MAIN_SOURCE.resolve("paymentexecution/application/usecase/CompletePaymentSubmissionService.java"));
        assertThat(core).contains("PaymentSubmissionCompletionPort", "IdempotencyReservationStore", "PaymentWalletLookupPort",
                "PaymentSubmissionDashboardPort", "requireReadyFor(", "saveAndProject(")
                .doesNotContain("KfeTransactionEntity", "KfeTransactionResponse", "KfeResponseMapper", "KfeDashboardPublisher", "@Transactional");
        String helper = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/in/KfeTransactionIdempotencyUseCase.java"));
        assertThat(helper).doesNotContain("void complete(", "KfeTransactionEntity");
    }

    @Test
    void idempotencyReservationAndReplayUsePortsWithoutRecoveringAnAbortedTransaction() throws IOException {
        String submit = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/in/KfeSubmitTransactionUseCase.java"));
        assertThat(submit).contains("PreflightPaymentUseCase", "ReservePaymentIdempotencyUseCase",
                "ISOLATION_READ_COMMITTED", "findByIdAndUserId(transactionId, userId)")
                .doesNotContain("DataIntegrityViolationException", "ConstraintViolationException",
                        "idempotencyUseCase.reserve(", "idempotencyUseCase.find(", "transactionRepository.findById(");
        String helper = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/in/KfeTransactionIdempotencyUseCase.java"));
        assertThat(helper).contains("GetIdempotentPaymentUseCase", "KFE_TX_REQUEST")
                .doesNotContain("KfeTransactionRepository", "IdempotencyReservationStore", "existingResponse(");
        for (String file : List.of("GetIdempotentPaymentService.java", "ReservePaymentIdempotencyService.java")) {
            String core = Files.readString(MAIN_SOURCE.resolve("paymentexecution/application/usecase/" + file));
            assertThat(core).contains("IdempotencyReservationStore")
                    .doesNotContain("KfeTransactionEntity", "KfeResponseMapper", "KfeSubmitTransactionRequest",
                            "@Transactional", "REQUIRES_NEW", "DataIntegrityViolationException");
        }
    }

    @Test
    void preflightAndAuthorizationAreCoreUseCasesBehindPorts() throws IOException {
        String submit = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/in/KfeSubmitTransactionUseCase.java"));
        assertThat(submit).contains("PreflightPaymentUseCase", "preflight.preflight(")
                .doesNotContain("KfeTransactionAuthorizationUseCase", "KfeTransactionRequestValidator",
                        "KfePlatformOnchainDestinationRouter", "KfeTransactionIdempotencyUseCase",
                        "PaymentWalletsUseCase", "GetIdempotentPaymentUseCase");
        String core = Files.readString(MAIN_SOURCE.resolve("paymentexecution/application/usecase/PreflightPaymentService.java"));
        assertThat(core).contains("PaymentWalletsUseCase", "PaymentCanonicalDestinationPort",
                "PaymentRequestFingerprintPort", "GetIdempotentPaymentUseCase", "AuthorizePaymentUseCase")
                .doesNotContain("KfeTransaction", "@Transactional", "TransactionTemplate", "PaymentLedgerPort");
        String authorization = Files.readString(MAIN_SOURCE.resolve("paymentexecution/application/usecase/AuthorizePaymentService.java"));
        assertThat(authorization).contains("PaymentAuthorizationPolicy", "PaymentApprovalPort", "MissingLocalPaymentFactor")
                .doesNotContain("StructuredPlatformException", "HttpStatus", "KfeSubmitTransactionRequest");
    }

    @Test
    void executionIdempotencyMappingMatchesTheExistingPerUserDatabaseConstraint() throws Exception {
        var entity = com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity.class;
        var column = entity.getDeclaredField("idempotencyKey").getAnnotation(jakarta.persistence.Column.class);
        assertThat(column.unique()).isFalse();
        var constraints = entity.getAnnotation(jakarta.persistence.Table.class).uniqueConstraints();
        assertThat(constraints).anySatisfy(constraint -> {
            assertThat(constraint.name()).isEqualTo("unique_user_idempotency");
            assertThat(constraint.columnNames()).containsExactly("user_id", "idempotency_key");
        });
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V12__kfe_core.sql"));
        assertThat(migration).contains("CONSTRAINT unique_user_idempotency UNIQUE (user_id, idempotency_key)");
    }

    @Test
    void workerClaimsUsePurePortsAndProductionExecutorPathOnly() throws IOException {
        String claimPort = Files.readString(MAIN_SOURCE.resolve("paymentexecution/application/port/out/ExecutionClaimPort.java"));
        assertThat(claimPort).contains("ExecutionClaim", "claimDue", "claimImmediate", "heartbeat")
                .doesNotContain("org.springframework", "jakarta.persistence", "KfeExecutionOutboxService");
        String facade = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/in/KfeExecutionOutboxService.java"));
        assertThat(facade).contains("ExecutionClaimPort").doesNotContain("@Transactional", "repository.claimDue(");
        String processor = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/in/KfeExecutionOutboxProcessor.java"));
        assertThat(processor).contains("ProcessExecutionUseCase")
                .doesNotContain("InlineOnchainOutboundExecutor", "InlineLightningOutboundExecutor", "sendOnchain(", "payLightning(",
                        "KfeExecutionTransactionHelper", "KfeRailExecution", "isRetryable(", "markFinalFailure(");
        String entry = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/in/transaction/ProcessExecutionAdapter.java"));
        assertThat(entry).contains("isActualTransactionActive()", "implements ProcessExecutionUseCase")
                .doesNotContain("@Transactional");
        for (String path : List.of("paymentexecution/adapters/in/scheduling/KfeExecutionOutboxWorker.java",
                "paymentexecution/adapters/out/messaging/OutboxExecutionCommandDispatcher.java")) {
            assertThat(Files.readString(MAIN_SOURCE.resolve(path)))
                    .contains("ExecutionClaimPort", "ProcessExecutionUseCase", "isActualTransactionActive()")
                    .doesNotContain("KfeExecutionOutboxService", "KfeExecutionOutboxProcessor", "exception.getMessage()");
        }
        String service = Files.readString(MAIN_SOURCE.resolve("paymentexecution/application/usecase/ProcessExecutionService.java"));
        assertThat(service).contains("ExecutionPreparationPort", "ExternalExecutionPort", "ExecutionOutcomePort")
                .doesNotContain("catch (", "LndRestLightningClient", "KfeRailExecution", "KfeExecutionTransactionHelper");
    }

    @Test
    void executionRecoveryUsesPurePoliciesAndNeverRefundsBasedOnInputProbes() throws IOException {
        String helper = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/out/execution/KfeExecutionTransactionHelper.java"));
        assertThat(helper).contains("ExecutionRecoveryPolicy", "OutboundConflictPolicy",
                        "requireOutboxTransaction(outbox, transactionId)", "requireSourceWallet(tx, sourceWalletId)")
                .doesNotContain("releaseConflictedReserve", "tryReconcileConflicted", "allInputsFree",
                        ".queryOutpoint(", ".getRawTransaction(", ".findReplacementTxid(", ".findReplacementInWallet(",
                        "outbox.getAttempts() + 1", "1L <<");
        String monitor = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/in/scheduling/KfeOutboundConfirmationMonitor.java"));
        assertThat(monitor).contains("markOutboundDisappeared(")
                .doesNotContain("allFree", ".queryOutpoint(", ".getRawTransaction(", ".findReplacementTxid(",
                        ".findReplacementInWallet(", ".markFinalFailure(");
        String policy = Files.readString(MAIN_SOURCE.resolve("paymentexecution/domain/policy/OutboundConflictPolicy.java"));
        assertThat(policy).contains("REORG_RECONCILIATION", "REQUIRES_RECONCILIATION")
                .doesNotContain("Optional.of(ExecutionStatus.CONFLICTED_REFUNDED)");
    }

    @Test
    void preparationAndFeesDelegateDecisionsToPurePolicies() throws IOException {
        String helper = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/out/execution/KfeExecutionTransactionHelper.java"));
        assertThat(helper).contains("OutboundPreparationPolicy", "ExecutionFeePolicy", "preparationPolicy.decide(",
                        "preparationPolicy.resolveFeeRate(", "FEE_POLICY.reconcile(", "FEE_POLICY.validateBeforeBroadcast(")
                .doesNotContain("quoteError.getMessage()", "Unsupported KFE outbox operation \" +",
                        "tx.getReceiverAmountSats() *", "tx.getNetworkFeeSats() + vbytes");
    }

    @Test
    void outboundPreparationBindsTheEnvelopeAndUsesAFreshOwnedSourceProjection() throws IOException {
        String helper = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/out/execution/KfeExecutionTransactionHelper.java"));
        String preparation = helper.substring(helper.indexOf("public PreparationResult prepare("),
                helper.indexOf("public void recordOutboundBroadcast("));
        assertThat(preparation).contains("envelopeReader.read(", "bindingPolicy.matches(",
                        "executionSourceWallets.findOwned(", "sourceWallet.usableFor(",
                        "tx.getExternalReference()", "ExecutionEnvelopeReader.InvalidExecutionEnvelope")
                .doesNotContain("walletRepository.findById(", "getMessage()", "payload.path(", "markFinalFailure(outbox.getId()");
        String source = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/out/persistence/JpaExecutionSourceWalletAdapter.java"));
        assertThat(source).contains("Propagation.MANDATORY", "w.id = :id and w.userId = :owner", "Object[].class")
                .doesNotContain("PESSIMISTIC", "setLockMode", "entityManager.find(");
    }

    @Test
    void submitUsesRequestLinkPortWithoutExposingRecipientRequestEntities() throws IOException {
        String source = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/in/KfeSubmitTransactionUseCase.java"));
        assertThat(source).contains("PaymentRequestLinkUseCase", "PreparePaymentRequestLinkCommand", "CompletePaymentRequestLinkCommand")
                .doesNotContain("KfePaymentRequestEntity", "KfeInternalPaymentRequestSettlementUseCase", ".markPaid(", "lockAndValidate(");
        assertThat(MAIN_SOURCE.resolve("application/transaction/KfeInternalPaymentRequestSettlementUseCase.java")).doesNotExist();
    }

    @Test
    void submitDelegatesFinancialReservationToTheInputPort() throws IOException {
        String source = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/in/KfeSubmitTransactionUseCase.java"));
        assertThat(source).contains("ReservePaymentFundsUseCase", "ReservePaymentFundsCommand")
                .doesNotContain("PaymentLedgerPort", "PaymentLiquidityPort", "reserveAndLock(", "WalletLock",
                        "KFE_TRANSACTION_LOCKED");
        String core = Files.readString(MAIN_SOURCE.resolve("paymentexecution/application/usecase/ReservePaymentFundsService.java"));
        assertThat(core).contains("PaymentFundsReservationStatePort", "requireReadyFor(", "lockOwnedSource(")
                .doesNotContain("KfeTransactionEntity", "KfeWalletEntity", "@Transactional");
    }

    @Test
    void submitDelegatesIntentCreationAndInternalSettlement() throws IOException {
        String source = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/in/KfeSubmitTransactionUseCase.java"));
        assertThat(source).contains("CreatePaymentIntentUseCase", "RouteLockedPaymentUseCase")
                .doesNotContain("new KfeTransactionEntity(", "private void settleInternal(",
                        ".settleReservedDebit(", ".creditAvailable(", ".settleFee(",
                        "notifyInternalTransferSent(", "notifyInternalTransferReceived(",
                        "transactionReference(", "PaymentFeeSettlementPort");
        String settlement = Files.readString(MAIN_SOURCE.resolve("paymentexecution/application/usecase/SettleInternalPaymentService.java"));
        assertThat(settlement).contains("InternalPaymentSettlementStatePort", "requireReadyFor(")
                .doesNotContain("KfeTransactionEntity", "KfeWalletEntity", "FinancialNotificationPort", "@Transactional");
    }

    @Test
    void routingOwnsInternalOrExternalDecisionWithoutLegacySubmitEffects() throws IOException {
        String submit = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/in/KfeSubmitTransactionUseCase.java"));
        assertThat(submit).doesNotContain("routeLockedTransaction(", "maybeNotifyVaultMesh(", "KfeTransactionOutboxUseCase",
                "KfeVaultMeshIntentService", "FinancialNotificationPort", "PaymentStatementPort", "SettleInternalPaymentUseCase",
                "KFE_TRANSACTION_EXECUTING", "saveAndFlush(");
        String core = Files.readString(MAIN_SOURCE.resolve("paymentexecution/application/usecase/RouteLockedPaymentService.java"));
        assertThat(core).contains("SettleInternalPaymentUseCase", "ExecutionCommandStore", "PaymentRoutingStatePort", "requireReadyFor(")
                .doesNotContain("ExecutionCommandDispatcher", "KfeTransactionEntity", "KfeSubmitTransactionRequest", "@Transactional");
        assertThat(MAIN_SOURCE.resolve("application/transaction/KfeTransactionOutboxUseCase.java")).doesNotExist();
    }

    @Test
    void removedCancellationBridgesCannotReturn() throws IOException {
        for (String removed : List.of(
                "service/KfeTransactionCancellationService.java",
                "paymentexecution/adapters/out/legacy/LegacyPaymentCancellationAdapter.java",
                "paymentexecution/application/port/out/PaymentCancellationPort.java")) {
            assertThat(MAIN_SOURCE.resolve(removed)).doesNotExist();
        }
        assertNoForbiddenImports(javaFilesUnder(MAIN_SOURCE), List.of(
                "com.kerosene.kfe.legacy.application.service.KfeTransactionCancellationService",
                "com.kerosene.kfe.paymentexecution.adapters.out.legacy.LegacyPaymentCancellationAdapter",
                "com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationPort"));
    }

    @Test
    void legacyPaymentRequestServiceUsesTheInputPort() throws IOException {
        String source = Files.readString(MAIN_SOURCE.resolve("paymentrequest/adapters/in/KfePaymentRequestService.java"));
        assertThat(source).contains("CancelPaymentRequestUseCase")
                .doesNotContain("KfeTransactionCancellationService", "CancelPaymentService",
                        "TransactionalPaymentCancellationAdapter", "PaymentRequestCancellationStatePort",
                        "PaymentRequestCancellationAuditPort");
    }

    @Test
    void cancellationEligibilityDoesNotDependOnTheCommandFacadeOrResponseMapper() throws IOException {
        String mapper = Files.readString(MAIN_SOURCE.resolve("paymentexecution/adapters/in/http/mapping/KfeResponseMapper.java"));
        assertThat(mapper).contains("PaymentCancellationHintsUseCase")
                .doesNotContain("KfeTransactionCancellationService", "ObjectProvider");
        String query = Files.readString(MAIN_SOURCE.resolve(
                "paymentexecution/adapters/out/persistence/JpaPaymentCancellationQueryAdapter.java"));
        assertThat(query).doesNotContain("KfeResponseMapper", "KfeTransactionCancellationService",
                "PaymentExecutionQueryRepository", "findByIdempotencyKeyStartingWith(", "findByPublicId(");
        String core = Files.readString(MAIN_SOURCE.resolve(
                "paymentexecution/application/usecase/CancelPaymentService.java"));
        assertThat(core).doesNotContain("findLinkedPaymentRequest", "findRelatedTransactions",
                "findByIdempotencyKeyStartingWith(", "findByPublicId(", "record CancellationHints");
    }

    @Test
    void packageInfoMarkersAreNotUsed() throws IOException {
        try (var paths = Files.walk(Path.of("src"))) {
            assertThat(paths.filter(path -> path.getFileName().toString().equals("package-info.java")).toList())
                    .isEmpty();
        }
    }

    private static List<Path> javaFilesInDirectoriesNamed(String directoryName) throws IOException {
        try (var paths = Files.walk(MAIN_SOURCE)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> containsDirectory(path, directoryName))
                    .toList();
        }
    }

    private static List<Path> javaFilesUnder(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
    }

    private static boolean containsDirectory(Path path, String directoryName) {
        for (Path segment : path) {
            if (segment.toString().equals(directoryName)) {
                return true;
            }
        }
        return false;
    }

    private static void assertNoForbiddenImports(List<Path> files, List<String> forbidden) throws IOException {
        StringBuilder violations = new StringBuilder();
        for (Path file : files) {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                for (String prefix : forbidden) {
                    if (trimmed.contains(prefix)) {
                        violations.append(file).append(": ").append(trimmed).append(System.lineSeparator());
                    }
                }
            }
        }
        assertThat(violations.toString())
                .as("forbidden dependency direction")
                .isEmpty();
    }

    private static void assertNoImportsMatching(List<Path> files, Pattern... forbidden) throws IOException {
        for (Path file : files) {
            for (String line : Files.readAllLines(file)) {
                if (!line.stripLeading().startsWith("import ")) {
                    continue;
                }
                for (Pattern pattern : forbidden) {
                    assertThat(pattern.matcher(line).find())
                            .as("forbidden dependency in %s: %s", file, line).isFalse();
                }
            }
        }
    }
}
