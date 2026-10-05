package com.kerosene.kfe.paymentexecution.adapters.out.settlement;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentGateEnvironmentPort;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

@Component
public class SpringPaymentGateEnvironmentAdapter implements PaymentGateEnvironmentPort {
    private final Environment environment;
    public SpringPaymentGateEnvironmentAdapter(Environment environment) { this.environment = environment; }
    @Override public boolean isProduction() {
        return Arrays.stream(environment.getActiveProfiles()).map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
    }
}
