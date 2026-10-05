package com.kerosene.kfe.paymentexecution.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalExecutionEvidenceTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void retainsEveryMarkerIndependently(int presentMarker) {
        ExternalExecutionEvidence evidence = new ExternalExecutionEvidence(
                presentMarker == 0, presentMarker == 1, presentMarker == 2,
                presentMarker == 3, presentMarker == 4, presentMarker == 5, presentMarker == 6);

        boolean[] markers = {
                evidence.preparedCiphertextPresent(), evidence.preparedHashPresent(),
                evidence.executionReferencePresent(), evidence.outboxProviderReferencePresent(),
                evidence.transactionProviderReferencePresent(), evidence.blockchainTxidPresent(),
                evidence.paymentHashPresent()
        };
        for (int index = 0; index < markers.length; index++) {
            assertThat(markers[index]).as("marker %s", index).isEqualTo(index == presentMarker);
        }
    }

    @Test
    void isAnImmutablePresenceOnlyValue() {
        ExternalExecutionEvidence empty = new ExternalExecutionEvidence(
                false, false, false, false, false, false, false);
        ExternalExecutionEvidence same = new ExternalExecutionEvidence(
                false, false, false, false, false, false, false);
        ExternalExecutionEvidence observed = new ExternalExecutionEvidence(
                false, false, false, false, false, true, false);

        assertThat(ExternalExecutionEvidence.class.isRecord()).isTrue();
        assertThat(empty).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(observed);
        assertThat(ExternalExecutionEvidence.class.getRecordComponents())
                .allSatisfy(component -> assertThat(component.getType()).isEqualTo(boolean.class));
    }
}
