package com.kerosene.kfe.bootstrap.config.onramp;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Loads the directory of third-party onramp/offramp URLs exposed by the beta experience.
 * Kerosene does not process fiat: these values are outbound links only, and entries without
 * an HTTP(S) scheme are omitted. Canonical providers are loaded first in stable order, followed
 * by additional properties discovered in enumerable Spring property sources.
 *
 * Configure any of:
 *   kfe.onramp.url.buy
 *   kfe.onramp.url.sell
 *   kfe.onramp.url.help
 *   kfe.onramp.url.moonpay
 *   kfe.onramp.url.transak
 *   ...
 * Env form: KFE_ONRAMP_URL_MOONPAY=https://...
 */
@Service
public class KfeOnrampDirectoryService {

    /** Spring property prefix shared by every configured provider link. */
    private static final String PREFIX = "kfe.onramp.url.";

    /** Immutable snapshot of configured provider keys and their normalized URL values. */
    private final Map<String, String> urls;

    /**
     * Reads and freezes the provider directory from the active Spring environment.
     *
     * @param environment property sources used to resolve canonical and custom provider URLs
     */
    public KfeOnrampDirectoryService(Environment environment) {
        this.urls = Map.copyOf(loadUrls(environment));
    }

    /**
     * Returns the immutable provider URL snapshot captured at service construction.
     *
     * @return map keyed by provider slug, containing only nonblank HTTP(S) links
     */
    public Map<String, String> urls() {
        return urls;
    }

    /**
     * Collects canonical keys first, then additional keys from enumerable property sources.
     * Property names are lowercased with a locale-independent rule; the first canonical value
     * wins when the same key is also discovered during enumeration.
     *
     * @param environment active Spring property resolver
     * @return insertion-ordered mutable map used to create the immutable service snapshot
     */
    private static Map<String, String> loadUrls(Environment environment) {
        Map<String, String> found = new LinkedHashMap<>();

        // Always try the canonical beta keys first so order is stable.
        putIfConfigured(environment, found, "buy");
        putIfConfigured(environment, found, "sell");
        putIfConfigured(environment, found, "help");
        putIfConfigured(environment, found, "moonpay");
        putIfConfigured(environment, found, "transak");
        putIfConfigured(environment, found, "ramp");
        putIfConfigured(environment, found, "onramper");
        putIfConfigured(environment, found, "stripe");
        putIfConfigured(environment, found, "coinbase");
        putIfConfigured(environment, found, "banxa");
        putIfConfigured(environment, found, "mercuryo");
        putIfConfigured(environment, found, "wert");
        putIfConfigured(environment, found, "gatefi");

        if (environment instanceof ConfigurableEnvironment configurable) {
            for (PropertySource<?> propertySource : configurable.getPropertySources()) {
                if (!(propertySource instanceof EnumerablePropertySource<?> enumerable)) {
                    continue;
                }
                for (String propertyName : enumerable.getPropertyNames()) {
                    if (propertyName == null || !propertyName.startsWith(PREFIX)) {
                        continue;
                    }
                    String key = propertyName.substring(PREFIX.length()).trim().toLowerCase(Locale.ROOT);
                    if (key.isEmpty() || found.containsKey(key)) {
                        continue;
                    }
                    putIfConfigured(environment, found, key);
                }
            }
        }

        return found;
    }

    /**
     * Adds a configured link when its value is nonblank and starts with HTTP or HTTPS.
     * Trimming is applied to the stored value; malformed or unsupported schemes are ignored.
     *
     * @param environment property resolver for the provider key
     * @param urls destination map preserving discovery order
     * @param key provider slug appended to the shared property prefix
     */
    private static void putIfConfigured(Environment environment, Map<String, String> urls, String key) {
        String value = environment.getProperty(PREFIX + key);
        if (value == null || value.isBlank()) {
            return;
        }
        String cleaned = value.trim();
        if (!cleaned.startsWith("https://") && !cleaned.startsWith("http://")) {
            return;
        }
        urls.put(key, cleaned);
    }
}
