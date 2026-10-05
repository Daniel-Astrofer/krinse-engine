package com.kerosene.kfe.paymentexecution.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentProposalTest {
    private final PaymentExecutionId id = new PaymentExecutionId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private final UUID source = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID destination = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @ParameterizedTest
    @CsvSource({"INTERNAL,INTERNAL", "ONCHAIN,OUTBOUND", "LIGHTNING,OUTBOUND", "ONCHAIN,INBOUND", "LIGHTNING,INBOUND"})
    void preservesLegacyProposalFieldOrderAndUntrimmedReferenceBytes(PaymentRail rail, PaymentDirection direction) {
        var proposal = proposal(rail, direction, source, destination, "  ext\tref  ", "  public id  ");
        assertThat(proposal.canonicalContent()).isEqualTo(
                "KFE_TX_PROPOSAL|00000000-0000-0000-0000-000000000001|7|" + rail.name() + "|" + direction.name()
                        + "|00000000-0000-0000-0000-000000000002|00000000-0000-0000-0000-000000000003"
                        + "|10000|9910|100|90|10100|  ext\tref  |  public id  ");
    }

    @Test
    void nullWalletsRemainLiteralNullButNullReferencesBecomeEmptyFields() {
        assertThat(proposal(PaymentRail.ONCHAIN, PaymentDirection.INBOUND, null, null, null, null).canonicalContent())
                .isEqualTo("KFE_TX_PROPOSAL|00000000-0000-0000-0000-000000000001|7|ONCHAIN|INBOUND|null|null|10000|9910|100|90|10100||");
    }

    @Test
    void blankReferencesAreNotCollapsedToNullOrTrimmed() {
        assertThat(proposal(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, source, destination, " ", "\t").canonicalContent())
                .endsWith("|10000|9910|100|90|10100| |\t");
        assertThat(proposal(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, source, destination, "", "").canonicalContent())
                .endsWith("|10000|9910|100|90|10100||");
    }

    @Test
    void delimitersAndUnicodeAreNotReencodedByThisCompatibilityExtraction() {
        assertThat(proposal(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, source, destination, "invoice|raw", "requisição-₿").canonicalContent())
                .endsWith("|invoice|raw|requisição-₿");
    }

    @Test
    void diagnosticRepresentationDoesNotLeakTheCanonicalPreimage() {
        var proposal = proposal(PaymentRail.INTERNAL, PaymentDirection.INTERNAL, source, destination,
                "sensitive-external-reference", "sensitive-public-id");
        assertThat(proposal.toString()).contains("REDACTED")
                .doesNotContain("sensitive-external-reference", "sensitive-public-id", proposal.canonicalContent());
    }

    private PaymentProposal proposal(PaymentRail rail, PaymentDirection direction, UUID src, UUID dest, String external, String publicId) {
        return new PaymentProposal(id, 7L, rail, direction, src, dest, 10_000L, 9_910L, 100L, 90L, 10_100L, external, publicId);
    }
}
