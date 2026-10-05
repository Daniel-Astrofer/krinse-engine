package com.kerosene.kfe.bootstrap.config.financial;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Imports the KFE runtime into a Spring Boot application only when the kfe profile is active.
 *
 * <p>The root Core application deliberately excludes com.kerosene.kfe from its default component scan.
 * This auto-configuration is discovered from the kfe-service jar and re-enables KFE components,
 * entities and repositories for the dedicated KFE runtime during the staged split.</p>
 */
@AutoConfiguration
@Profile("kfe")
@ConditionalOnProperty(name = "kfe.standalone", havingValue = "false", matchIfMissing = true)
@ComponentScan(basePackages = "com.kerosene.kfe")
@PropertySource("classpath:kfe-service-defaults.properties")
@EntityScan(basePackages = "com.kerosene.kfe.adapters.out.persistence.model")
@EnableJpaRepositories(basePackages = "com.kerosene.kfe.adapters.out.persistence.repository")
public class KfeServiceRuntimeConfiguration {
}
