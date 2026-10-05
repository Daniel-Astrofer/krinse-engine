package com.kerosene.kfe.wallet.adapters.out.bitcoin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.kerosene.common.service.AddressDerivationService;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared receive/change descriptor resolution for cold observe and on-chain balance probes.
 * Keeps default account path network-aware so fallback rebuilds do not diverge by caller.
 */
@Component
public class KfeWalletDescriptorResolver {

    /** Logger for unusable persisted descriptors and non-fatal descriptor rebuild failures. */
    private static final Logger log = LoggerFactory.getLogger(KfeWalletDescriptorResolver.class);
    /** Matches extended public keys embedded in supported descriptor text for network rewriting. */
    private static final Pattern XPUB_PATTERN = Pattern.compile(
            "\\b([xtyzuv]pub[1-9A-HJ-NP-Za-km-z]{20,})\\b");

    /** Derives network-specific extended public keys when converting stored wallet material. */
    private final AddressDerivationService addressDerivationService;
    /** Normalized network label used for default BIP84 account paths. */
    private final String bitcoinNetwork;

    /** Creates the resolver with shared address derivation and normalized network configuration. */
    public KfeWalletDescriptorResolver(
            AddressDerivationService addressDerivationService,
            @Value("${bitcoin.network:mainnet}") String bitcoinNetwork) {
        this.addressDerivationService = addressDerivationService;
        this.bitcoinNetwork = bitcoinNetwork != null ? bitcoinNetwork.trim().toLowerCase(Locale.ROOT) : "mainnet";
    }

    /** Default BIP84 account path for the configured network (without {@code m/} prefix). */
    /** Resolves the network coin type for the BIP84 account path, omitting the {@code m/} prefix. */
    public String defaultAccountPath() {
        return switch (bitcoinNetwork) {
            case "testnet", "testnet3", "testnet4", "signet", "regtest" -> "84h/1h/0h";
            default -> "84h/0h/0h";
        };
    }

    /**
     * Resolve a usable receive descriptor ({@code .../0/*}) for the wallet, or {@code null}.
     */
    /** Prefers a usable persisted descriptor, otherwise rebuilds a receive descriptor from xpub. */
    public String resolveReceiveDescriptor(KfeWalletEntity wallet) {
        if (wallet == null) {
            return null;
        }
        if (hasText(wallet.getDescriptor())) {
            String stored = rewriteDescriptorXpubs(stripChecksum(wallet.getDescriptor().trim()));
            if (isUsableOutputDescriptor(stored)) {
                return stored;
            }
            log.warn(
                    "[KFE Descriptor] stored descriptor unusable walletId={} len={}; rebuild from xpub",
                    wallet.getId(),
                    stored.length());
        }
        if (!hasText(wallet.getXpub())) {
            return null;
        }
        try {
            String xpub = addressDerivationService.toNetworkExtendedPublicKey(wallet.getXpub().trim());
            String fingerprint = hasText(wallet.getFingerprint())
                    ? wallet.getFingerprint().trim().toLowerCase(Locale.ROOT)
                    : "00000000";
            String accountPath = hasText(wallet.getDerivationPath())
                    ? wallet.getDerivationPath().trim().replaceFirst("^m/", "").replace("'", "h")
                    : defaultAccountPath();
            String origin = accountPath.isEmpty() || "m".equalsIgnoreCase(accountPath)
                    ? fingerprint
                    : fingerprint + "/" + accountPath;
            return "wpkh([" + origin + "]" + xpub + "/0/*)";
        } catch (RuntimeException exception) {
            log.debug(
                    "[KFE Descriptor] rebuild failed walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
            return null;
        }
    }

    /** Changes a receive branch descriptor ({@code /0/*}) to its change branch ({@code /1/*}). */
    public static String toChangeDescriptor(String receiveDescriptor) {
        if (receiveDescriptor == null || !receiveDescriptor.contains("/0/*")) {
            return null;
        }
        return receiveDescriptor.replace("/0/*", "/1/*");
    }

    /** Rewrites embedded extended public keys to the configured network's public-key version. */
    public String rewriteDescriptorXpubs(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            return descriptor;
        }
        Matcher matcher = XPUB_PATTERN.matcher(descriptor);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String raw = matcher.group(1);
            String rewritten;
            try {
                rewritten = addressDerivationService.toNetworkExtendedPublicKey(raw);
            } catch (RuntimeException ignored) {
                rewritten = raw;
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(rewritten));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** Performs structural usability checks without claiming full Bitcoin Core descriptor validation. */
    public static boolean isUsableOutputDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            return false;
        }
        String bare = stripChecksum(descriptor.trim());
        if (!bare.contains("(") || !bare.endsWith(")")) {
            return false;
        }
        return bare.contains("/*")
                || bare.startsWith("addr(")
                || bare.startsWith("raw(")
                || bare.matches(".*\\)/\\d+\\)$");
    }

    /** Removes the optional checksum suffix while preserving the descriptor body. */
    public static String stripChecksum(String descriptor) {
        if (descriptor == null) {
            return null;
        }
        int hash = descriptor.indexOf('#');
        return hash >= 0 ? descriptor.substring(0, hash) : descriptor;
    }

    /** Returns whether a persisted wallet value is non-null and nonblank. */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
