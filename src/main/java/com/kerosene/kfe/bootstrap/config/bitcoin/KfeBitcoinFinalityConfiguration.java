package com.kerosene.kfe.bootstrap.config.bitcoin;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link KfeBitcoinFinalityPolicy} from the {@code bitcoin.*} application properties. */
@Configuration
@EnableConfigurationProperties(KfeBitcoinFinalityPolicy.class)
public class KfeBitcoinFinalityConfiguration {
}
