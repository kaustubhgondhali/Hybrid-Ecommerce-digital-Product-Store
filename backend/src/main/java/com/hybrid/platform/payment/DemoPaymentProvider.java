package com.hybrid.platform.payment;

import com.hybrid.platform.common.crypto.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Demo gateway for development and testing until Razorpay is integrated. No money moves.
 * Refuses to start under the "prod" profile so it can never take real orders.
 */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "demo", matchIfMissing = true)
public class DemoPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(DemoPaymentProvider.class);

    public DemoPaymentProvider(Environment env) {
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("The demo payment provider cannot run with the 'prod' profile. "
                    + "Configure a real payment provider (PAYMENT_PROVIDER) before going live.");
        }
        log.warn("DEMO payment provider active: checkout payments are simulated and no money is collected.");
    }

    @Override
    public String code() {
        return "demo";
    }

    @Override
    public boolean isDemo() {
        return true;
    }

    @Override
    public ProviderOrder createOrder(String receipt, long amountMinor, String currency) {
        return new ProviderOrder("demo_order_" + Hashing.randomCode(14), Map.of("mode", "demo"));
    }

    @Override
    public RefundResult refund(Payment payment, long amountMinor) {
        return new RefundResult("demo_refund_" + Hashing.randomCode(14));
    }
}
