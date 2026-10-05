package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionOutcomePort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionPreparationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExternalExecutionPort;
import com.kerosene.kfe.paymentexecution.application.usecase.ProcessExecutionService;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.KfeRailExecution;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Collection;

/** Wires execution workers and bridges legacy rail executors when modern ports are absent. */
@Configuration
public class PaymentExecutionWorkerConfiguration {
    /**
     * Registers execution processing with modern executors when present, otherwise wraps legacy ones.
     *
     * <p>The selected adapter list is immutable and passed to the worker's claim/preparation/outcome flow.
     */
    @Bean
    public ProcessExecutionService processExecutionService(
            ExecutionClaimPort claims, ExecutionPreparationPort preparation,
            List<ExternalExecutionPort> executors, List<KfeRailExecution> legacyExecutors,
            ExecutionOutcomePort outcomes) {
        List<ExternalExecutionPort> selected = executors.isEmpty()
                ? legacyExecutors.stream()
                        .map(com.kerosene.kfe.paymentexecution.adapters.out.execution.LegacyExternalExecutionAdapter::new)
                        .map(executor -> (ExternalExecutionPort) executor)
                        .toList()
                : List.copyOf(executors);
        return new ProcessExecutionService(claims, preparation, selected, outcomes);
    }

    /** Compatibility factory for callers that still supply only legacy rail execution adapters. */
    public ProcessExecutionService processExecutionService(
            ExecutionClaimPort claims, ExecutionPreparationPort preparation,
            Collection<KfeRailExecution> legacyExecutors, ExecutionOutcomePort outcomes) {
        List<ExternalExecutionPort> bridges = legacyExecutors.stream()
                .map(com.kerosene.kfe.paymentexecution.adapters.out.execution.LegacyExternalExecutionAdapter::new)
                .map(executor -> (ExternalExecutionPort) executor)
                .toList();
        return new ProcessExecutionService(claims, preparation, bridges, outcomes);
    }
}
