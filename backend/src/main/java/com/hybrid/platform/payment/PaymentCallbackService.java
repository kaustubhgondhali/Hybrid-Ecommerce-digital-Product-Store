package com.hybrid.platform.payment;

import com.hybrid.platform.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Turns gateway callbacks (the browser returning from checkout, and webhooks) into payment events. Calls to the
 * gateway happen here, outside any database transaction; {@link PaymentService} then records the outcome.
 * Both paths use the same event ids, so whichever arrives second is ignored.
 */
@Service
public class PaymentCallbackService {

    private static final Logger log = LoggerFactory.getLogger(PaymentCallbackService.class);

    private final PaymentService payments;
    private final PaymentProvider provider;

    public PaymentCallbackService(PaymentService payments, PaymentProvider provider) {
        this.payments = payments;
        this.provider = provider;
    }

    /** The browser came back from the gateway's checkout. Its claims are only used after the signature checks out. */
    public void confirmCheckoutReturn(Long orderId, Map<String, String> params) {
        Payment p = payments.findByOrder(orderId)
                .filter(x -> provider.code().equals(x.getProvider()))
                .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
        String providerPaymentId = provider.verifyCheckoutReturn(p.getProviderOrderId(), params);
        apply(p.getProviderOrderId(), provider.settle(providerPaymentId));
    }

    public void handleWebhook(String providerCode, byte[] body, String signature) {
        if (!provider.code().equals(providerCode)) {
            throw ApiException.notFound("NOT_FOUND", "Not found");
        }
        Optional<PaymentProvider.Notice> notice = provider.parseWebhook(body, signature);
        if (notice.isEmpty()) {
            return;
        }
        PaymentProvider.Notice n = notice.get();
        if (n.providerOrderId() == null || !payments.knowsProviderOrder(n.providerOrderId())) {
            log.info("Ignoring {} webhook for unknown order {}", providerCode, n.providerOrderId());
            return;
        }
        if (n.failed()) {
            payments.confirmFailure(n.providerOrderId(), failedEventId(n.providerPaymentId()), reason(n.failureReason()));
        } else {
            apply(n.providerOrderId(), provider.settle(n.providerPaymentId()));
        }
    }

    private void apply(String providerOrderId, PaymentProvider.Settlement s) {
        if (!providerOrderId.equals(s.providerOrderId())) {
            log.error("Payment {} belongs to {} not {}", s.providerPaymentId(), s.providerOrderId(), providerOrderId);
            throw ApiException.badRequest("PAYMENT_ORDER_MISMATCH", "This payment does not belong to this order");
        }
        switch (s.status()) {
            case CAPTURED -> payments.confirmSuccess(providerOrderId, s.providerPaymentId(),
                    "captured:" + s.providerPaymentId(), s.amountMinor());
            case FAILED -> payments.confirmFailure(providerOrderId, failedEventId(s.providerPaymentId()), reason(s.failureReason()));
            case PENDING -> log.info("Payment {} for {} is not captured yet", s.providerPaymentId(), providerOrderId);
        }
    }

    private static String failedEventId(String providerPaymentId) {
        return "failed:" + providerPaymentId;
    }

    private static String reason(String failureReason) {
        if (failureReason == null || failureReason.isBlank()) return "Payment declined";
        return failureReason.length() > 290 ? failureReason.substring(0, 290) : failureReason;
    }
}
