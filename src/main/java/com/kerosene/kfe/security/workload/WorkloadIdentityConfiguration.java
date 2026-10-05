package com.kerosene.kfe.security.workload;

import com.kerosene.common.security.workload.InternalServiceAuthenticationFilter;
import com.kerosene.common.security.workload.InternalServiceRestTemplateFactory;
import com.kerosene.common.security.workload.SpiffeX509Identity;
import com.kerosene.common.security.workload.TomcatSslContextAdapter;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(WorkloadIdentityProperties.class)
public class WorkloadIdentityConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "kerosene.workload-identity.enabled", havingValue = "true")
    SpiffeX509Identity spiffeX509Identity(WorkloadIdentityProperties properties) {
        return new SpiffeX509Identity(properties.toConfig());
    }

    @Bean
    InternalServiceRestTemplateFactory internalServiceRestTemplateFactory(
            WorkloadIdentityProperties properties,
            ObjectProvider<SpiffeX509Identity> spiffeIdentity,
            @Value("${kfe.internal.shared-secret:}") String legacySecret,
            @Value("${release.manifest.path:}") String manifestPath,
            @Value("${release.attestation.shared-secret:${RELEASE_ATTESTATION_SECRET:}}") String releaseSecret) {
        return new InternalServiceRestTemplateFactory(
                properties.toConfig(), spiffeIdentity.getIfAvailable(), legacySecret,
                manifestPath.isBlank() ? java.util.List.of() : java.util.List.of(
                        new com.kerosene.common.security.workload.ReleaseAttestationRequestInterceptor(
                                manifestPath, releaseSecret, properties.toConfig().ownSpiffeId())));
    }

    @Bean
    FilterRegistrationBean<InternalServiceAuthenticationFilter> internalServiceAuthenticationFilter(
            WorkloadIdentityProperties properties,
            @Value("${kfe.internal.shared-secret:}") String legacySecret) {
        InternalServiceAuthenticationFilter filter = new InternalServiceAuthenticationFilter(
                properties.toConfig(),
                legacySecret,
                List.of("/internal/kfe", "/kfe", "/api/admin/kfe", "/api/public/kfe"));
        FilterRegistrationBean<InternalServiceAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setOrder(Integer.MIN_VALUE + 100);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    @ConditionalOnProperty(name = "kerosene.workload-identity.enabled", havingValue = "true")
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> spiffeInternalConnector(
            WorkloadIdentityProperties properties,
            SpiffeX509Identity identity) {
        return factory -> factory.addAdditionalTomcatConnectors(createConnector(properties, identity));
    }

    static Connector createConnector(WorkloadIdentityProperties properties, SpiffeX509Identity identity) {
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(properties.getInternalPort());
        connector.setScheme("https");
        connector.setSecure(true);
        connector.setProperty("SSLEnabled", "true");
        connector.setProperty("maxThreads", "100");

        SSLHostConfig hostConfig = new SSLHostConfig();
        hostConfig.setProtocols("TLSv1.3");
        hostConfig.setCertificateVerification("required");
        hostConfig.setHonorCipherOrder(true);
        SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(
                hostConfig, SSLHostConfigCertificate.Type.UNDEFINED);
        certificate.setSslContext(new TomcatSslContextAdapter(
                identity.peerSslContext(), identity.source()));
        hostConfig.addCertificate(certificate);
        connector.addSslHostConfig(hostConfig);
        return connector;
    }
}
