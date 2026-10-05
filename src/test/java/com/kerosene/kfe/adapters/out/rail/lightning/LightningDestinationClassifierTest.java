package com.kerosene.kfe.adapters.out.rail.lightning;

import com.kerosene.kfe.adapters.out.rail.lnurl.LnurlPayResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LightningDestinationClassifierTest {

    @Test
    void classifiesBolt11() {
        assertThat(LightningDestinationClassifier.classify("lntb1m1p49n88hpp5abc").kind())
                .isEqualTo(LightningDestinationClassifier.Kind.BOLT11);
        assertThat(LightningDestinationClassifier.classify("LNBC1...").kind())
                .isEqualTo(LightningDestinationClassifier.Kind.BOLT11);
        assertThat(LightningDestinationClassifier.classify("lightning:lntb1abc").kind())
                .isEqualTo(LightningDestinationClassifier.Kind.BOLT11);
    }

    @Test
    void classifiesLnurl() {
        assertThat(LightningDestinationClassifier.classify(
                        "LNURL1DP68GURN8GHJ7UM9WFMXJCM99E3K7MF0V9CXJ0M385EKVCENXC6R2C35XVUKXEFCV5MKVV34X5EKZD3EV56NYD3HXQURZEPEXEJXXEPNXSCRVWFNV9NXZCN9XQ6XYEFHVGCXXCMMW4EXZURF9KHXXER9XCEXGETJV4EHX7RS9JQ8G")
                .kind()).isEqualTo(LightningDestinationClassifier.Kind.LNURL);
    }

    @Test
    void classifiesLightningAddress() {
        assertThat(LightningDestinationClassifier.classify("satoshi@example.com").kind())
                .isEqualTo(LightningDestinationClassifier.Kind.LIGHTNING_ADDRESS);
        assertThat(LightningDestinationClassifier.classify("lightning:user@strike.me").kind())
                .isEqualTo(LightningDestinationClassifier.Kind.LIGHTNING_ADDRESS);
    }

    @Test
    void classifiesKeysendPubkey() {
        String pubkey = "02" + "ab".repeat(32);
        assertThat(pubkey).hasSize(66);
        assertThat(LightningDestinationClassifier.classify(pubkey).kind())
                .isEqualTo(LightningDestinationClassifier.Kind.KEYSEND);
    }

    @Test
    void rejectsGarbage() {
        assertThat(LightningDestinationClassifier.classify("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"))
                .isNull();
        assertThat(LightningDestinationClassifier.classify("not-a-destination")).isNull();
        assertThat(LightningDestinationClassifier.classify("")).isNull();
    }

    @Test
    void unwrapsLightningUriAndBip21() {
        assertThat(LightningDestinationClassifier.classify("lightning:lntb1m1p49n88hpp5abc").kind())
                .isEqualTo(LightningDestinationClassifier.Kind.BOLT11);
        assertThat(LightningDestinationClassifier.classify(
                        "bitcoin:tb1qtest?amount=0.001&lightning=lntb1m1p49n88hpp5abc")
                .kind()).isEqualTo(LightningDestinationClassifier.Kind.BOLT11);
        assertThat(LightningDestinationClassifier.classify("  lntb1m1p49n88hpp5  ").kind())
                .isEqualTo(LightningDestinationClassifier.Kind.BOLT11);
    }

    @Test
    void lightningAddressToLnurlpUrl() {
        assertThat(LnurlPayResolver.lightningAddressToLnurlpUrl("alice@getalby.com"))
                .isEqualTo("https://getalby.com/.well-known/lnurlp/alice");
    }
}
