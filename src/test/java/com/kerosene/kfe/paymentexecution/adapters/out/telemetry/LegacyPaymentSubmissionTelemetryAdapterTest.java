package com.kerosene.kfe.paymentexecution.adapters.out.telemetry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyPaymentSubmissionTelemetryAdapterTest {
    @Test
    void preservesTheExistingOperationalMessageWithoutReferencesOrSecrets() {
        var logger = (Logger) LoggerFactory.getLogger(LegacyPaymentSubmissionTelemetryAdapter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            new LegacyPaymentSubmissionTelemetryAdapter().feeReserveRaised(12L, 123L, 5L, 6);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.getFirst().getFormattedMessage()).isEqualTo(
                    "[KFE Submit] raising on-chain fee reserve clientFeeSats=12 floorFeeSats=123 feeRate=5 targetBlocks=6");
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void optionalFeeHintsRemainNullable() {
        var logger = (Logger) LoggerFactory.getLogger(LegacyPaymentSubmissionTelemetryAdapter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            new LegacyPaymentSubmissionTelemetryAdapter().feeReserveRaised(0L, 123L, null, null);
            assertThat(appender.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).isEqualTo(
                    "[KFE Submit] raising on-chain fee reserve clientFeeSats=0 floorFeeSats=123 feeRate=null targetBlocks=null");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
