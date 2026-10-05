package com.kerosene.kfe.bootstrap.config.persistence;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import com.kerosene.common.persistence.StringCryptoConverter;
import com.kerosene.common.security.StringColumnCryptoPort;

/**
 * Wires {@link StringCryptoConverter}'s static crypto port for KFE standalone.
 *
 * <p>JPA often instantiates converters via {@code @Convert(converter=...)} without Spring,
 * so injection must land on the static holder used by the converter.
 */
@Configuration
public class KfeStringCryptoConverterConfig {

    /** Logger for the one-time converter wiring event; secret material is never logged. */
    private static final Logger log = LoggerFactory.getLogger(KfeStringCryptoConverterConfig.class);

    /** Spring-managed crypto port installed into the converter's static holder. */
    private final StringColumnCryptoPort cryptoPort;

    /**
     * Receives the standalone KFE column encryption implementation.
     *
     * @param cryptoPort crypto port used by JPA converter instances created outside Spring
     */
    public KfeStringCryptoConverterConfig(StringColumnCryptoPort cryptoPort) {
        this.cryptoPort = cryptoPort;
    }

    /**
     * Installs the injected port into the converter's static holder after dependency wiring.
     * This adapts JPA converter lifecycle, where {@code @Convert} may instantiate the converter
     * without Spring managing that instance.
     */
    @PostConstruct
    void wireConverter() {
        // Converter is not a Spring bean under scanBasePackages=com.kerosene.kfe — set static port.
        StringCryptoConverter holder = new StringCryptoConverter();
        holder.setCryptoPort(cryptoPort);
        log.info("[KfeStringCryptoConverterConfig] StringCryptoConverter crypto port wired.");
    }
}
