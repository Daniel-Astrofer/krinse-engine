package com.kerosene.kfe.wallet.adapters.out.bitcoin;

import org.junit.jupiter.api.Test;
import com.kerosene.common.service.AddressDerivationService;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KfeWalletDescriptorResolverTest {

    @Test
    void defaultAccountPathIsNetworkAware() {
        AddressDerivationService derivation = mock(AddressDerivationService.class);
        assertThat(new KfeWalletDescriptorResolver(derivation, "mainnet").defaultAccountPath())
                .isEqualTo("84h/0h/0h");
        assertThat(new KfeWalletDescriptorResolver(derivation, "testnet4").defaultAccountPath())
                .isEqualTo("84h/1h/0h");
        assertThat(new KfeWalletDescriptorResolver(derivation, "signet").defaultAccountPath())
                .isEqualTo("84h/1h/0h");
    }

    @Test
    void rebuildFromXpubUsesNetworkDefaultWhenPathMissing() {
        AddressDerivationService derivation = mock(AddressDerivationService.class);
        when(derivation.toNetworkExtendedPublicKey(anyString())).thenAnswer(inv -> inv.getArgument(0));
        KfeWalletDescriptorResolver resolver = new KfeWalletDescriptorResolver(derivation, "testnet4");

        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(UUID.randomUUID());
        wallet.setKind(KfeWalletKind.WATCH_ONLY);
        wallet.setXpub("tpubDTestOnlyMaterialForUnitTestXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
        wallet.setFingerprint("aabbccdd");

        String desc = resolver.resolveReceiveDescriptor(wallet);
        assertThat(desc).isNotNull();
        assertThat(desc).contains("84h/1h/0h");
        assertThat(desc).endsWith("/0/*)");
    }

    @Test
    void toChangeDescriptorSwapsReceiveBranch() {
        assertThat(KfeWalletDescriptorResolver.toChangeDescriptor("wpkh([fp/84h/1h/0h]tpub/0/*)"))
                .isEqualTo("wpkh([fp/84h/1h/0h]tpub/1/*)");
        assertThat(KfeWalletDescriptorResolver.toChangeDescriptor("addr(tb1qxyz)")).isNull();
    }
}
