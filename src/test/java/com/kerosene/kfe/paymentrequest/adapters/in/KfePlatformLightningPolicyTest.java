package com.kerosene.kfe.paymentrequest.adapters.in;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;
import com.kerosene.kfe.adapters.in.http.dto.paymentrequest.KfePublicPaymentRequestResponse;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfePlatformLightningPolicyTest {

    private static final String BOLT11 =
            "lntb93330n1p49ntsepp56vdfva66vjqdlgd7yqap04kfqnpjwp4hy47zu9uphxwhjuqpkucsdq6gdhkuarpypqhxum9va6hyctyvycqzzsxqzursp5xkcrt803vccua9saffv4hqsef3aj5stl72nswd0cv4h7r8q3sy6s9qxpqysgqxk0hrkpjr423444rapgx0ccsxdn023zvlqtvzw4t2pnq3w6f977qhgejgk5rq5ne0hrutuhwm2yhaz94smd0krt57ayhlwh38hkcp4cqfamdne";

    private final KfePaymentRequestRepository repository = mock(KfePaymentRequestRepository.class);
    private final KfePaymentRequestService paymentRequestService = mock(KfePaymentRequestService.class);
    private KfePlatformLightningPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new KfePlatformLightningPolicy(repository, paymentRequestService);
    }

    @Test
    void allowsExternalLightningInvoice() {
        when(repository.findFirstByPaymentRequestIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(repository.findFirstByPaymentHashIgnoreCase(anyString())).thenReturn(Optional.empty());

        policy.rejectLightningOutboundIfPlatformOwned(BOLT11);

        // classified bolt11 + raw fallback may each hit the repository once
        verify(repository, org.mockito.Mockito.atLeastOnce())
                .findFirstByPaymentRequestIgnoreCase(anyString());
    }

    @Test
    void deniesPlatformOwnedBolt11() {
        KfePaymentRequestEntity entity = entity();
        when(repository.findFirstByPaymentRequestIgnoreCase(anyString())).thenReturn(Optional.of(entity));

        StructuredPlatformException ex = assertThrows(
                StructuredPlatformException.class,
                () -> policy.rejectLightningOutboundIfPlatformOwned(BOLT11));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCodes.LEDGER_PLATFORM_LIGHTNING_DENIED);
        assertThat(ex.getData().toString()).contains("8ou3btbr");
        verify(repository, never()).findFirstByPaymentHashIgnoreCase(anyString());
    }

    @Test
    void resolvesByPaymentHash() {
        String hash = "d31a96775a6480dfa1be203a17d6c904c32706b7257c2e1781b99d797001b731";
        KfePaymentRequestEntity entity = entity();
        when(repository.findFirstByPaymentRequestIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(repository.findFirstByPaymentHashIgnoreCase(hash)).thenReturn(Optional.of(entity));
        KfePublicPaymentRequestResponse response = mock(KfePublicPaymentRequestResponse.class);
        when(paymentRequestService.publicGet("8ou3btbrv9wdqgipgcicxh8k")).thenReturn(response);

        assertThat(policy.resolvePlatformInvoice(hash)).contains(response);
    }

    private static KfePaymentRequestEntity entity() {
        KfePaymentRequestEntity entity = new KfePaymentRequestEntity();
        entity.setPublicId("8ou3btbrv9wdqgipgcicxh8k");
        entity.setUserId(1L);
        entity.setWalletId(UUID.randomUUID());
        entity.setAddress("ln:short");
        entity.setPaymentRequest(BOLT11);
        entity.setPaymentHash("d31a96775a6480dfa1be203a17d6c904c32706b7257c2e1781b99d797001b731");
        entity.setRail(KfeRail.LIGHTNING);
        entity.setStatus(KfePaymentRequestStatus.OPEN);
        entity.setAmountSats(9333L);
        return entity;
    }
}
