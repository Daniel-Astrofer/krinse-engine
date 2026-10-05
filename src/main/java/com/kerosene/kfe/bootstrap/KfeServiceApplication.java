package com.kerosene.kfe.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Standalone Spring Boot entry point for the KFE financial service.
 * Scanning, persistence, scheduling, and default service properties are enabled only when
 * {@code kfe.standalone=true}; embedded use can provide its own application context instead.
 */
@SpringBootApplication(
        scanBasePackages = "com.kerosene.kfe",
        exclude = UserDetailsServiceAutoConfiguration.class)
@ConditionalOnProperty(name = "kfe.standalone", havingValue = "true")
@PropertySource("classpath:kfe-service-defaults.properties")
@EntityScan(basePackages = "com.kerosene.kfe.adapters.out.persistence.model")
@EnableJpaRepositories(basePackages = "com.kerosene.kfe.adapters.out.persistence.repository")
@EnableScheduling
public class KfeServiceApplication {

    /**
     * Starts the standalone KFE application context with the supplied command-line arguments.
     *
     * @param args Spring Boot command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(KfeServiceApplication.class, args);
    }
}
