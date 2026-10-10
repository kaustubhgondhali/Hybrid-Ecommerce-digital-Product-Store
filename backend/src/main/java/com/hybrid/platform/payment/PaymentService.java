package com.hybrid.platform.payment;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.crypto.Hashing;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.payment.PaymentRepositories.PaymentEventRepository;
import com.hybrid.platform.payment.PaymentRepositories.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Sole authority on whether money was collected. Order state changes only in response to events
 * published here, never in response to anything the browser claims.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    public static final String COD = "cod";

    public record PaymentInit(Long paymentId, String provider, boolean demo, String providerOrderId,
                              long amountMinor, String currency, Map<String, Object> clientParams) {
    }

    public record PaymentView(Long id, String provider, String providerOrderId, String providerPaymentId, String status,
                              long amountMinor, long refundedMinor, String currency, String failureReason, Instant createdAt) {
    }

    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final PaymentProvider provider;
    private final ApplicationEventPublisher publisher;

    public PaymentService(PaymentRepository payments, PaymentEventRepository events, PaymentProvider provider,
                          ApplicationEventPublisher publisher) {
        this.payments = payments;
        this.events = events;
        this.provider = provider;
        this.publisher = publisher;
    }

    public boolean isDemo() {
        return provider.isDemo();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentInit start(Long orderId, Storefront sf, long amountMinor, String currency, String receipt) {
        Payment existing = payments.findByOrderId(orderId).orElse(null);
        if (existing != null) {
            return init(existing, Map.of("mode", provider.isDemo() ? "demo" : "live"));
        }
        PaymentProvider.ProviderOrder po = provider.createOrder(receipt, amountMinor, currency);
        Payment p = payments.save(new Payment(orderId, sf, provider.code(), po.providerOrderId(), amountMinor, currency));
        return init(p, po.clientParams());
    }

    /** Cash on delivery: records an unpaid payment that the store marks as collected when the parcel is delivered. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void startCashOnDelivery(Long orderId, Storefront sf, long amountMinor, String currency) {
        if (payments.findByOrderId(orderId).isEmpty()) {
            payments.save(new Payment(orderId, sf, COD, "cod_" + Hashing.randomCode(20), amountMinor, currency));
        }
    }

    /** Admin confirmed the courier collected the cash. Returns true if this call recorded the collection. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordCashCollected(Long orderId) {
        Payment p = payments.findByOrderId(orderId)
                .filter(x -> COD.equals(x.getProvider()))
                .orElseThrow(() -> ApiException.conflict("NOT_COD", "This order is not a cash-on-delivery order"));
        if (p.getStatus() == Payment.Status.SUCCEEDED) return false;
        p.setStatus(Payment.Status.SUCCEEDED);
        p.setProviderPaymentId("cash");
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<PaymentInit> currentInit(Long orderId) {
        return payments.findByOrderId(orderId).map(p -> init(p, Map.of("mode", provider.isDemo() ? "demo" : "live")));
    }

    /** Called by a verified provider callback (webhook / signature-checked return) or by the demo simulator. */
    @Transactional
    public void confirmSuccess(String providerOrderId, String providerPaymentId, String providerEventId, long paidAmountMinor) {
        if (events.existsByProviderAndProviderEventId(provider.code(), providerEventId)) {
            return;
        }
        Payment p = payments.findByProviderAndProviderOrderId(provider.code(), providerOrderId)
                .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
        events.save(new PaymentEventRecord(provider.code(), providerEventId, "payment.succeeded", p.getId()));
        if (p.getStatus() == Payment.Status.SUCCEEDED) {
            return;
        }
        if (paidAmountMinor != p.getAmountMinor()) {
            log.error("Payment {} amount mismatch: expected {} got {}", p.getId(), p.getAmountMinor(), paidAmountMinor);
            p.setStatus(Payment.Status.FAILED);
            p.setFailureReason("Amount mismatch");
            publisher.publishEvent(new PaymentEvents.PaymentFailed(p.getOrderId(), p.getId(), "Amount mismatch"));
            return;
        }
        p.setStatus(Payment.Status.SUCCEEDED);
        p.setProviderPaymentId(providerPaymentId);
        p.setFailureReason(null);
        publisher.publishEvent(new PaymentEvents.PaymentSucceeded(p.getOrderId(), p.getId(), p.getAmountMinor(), p.getCurrency()));
    }

    @Transactional
    public void confirmFailure(String providerOrderId, String providerEventId, String reason) {
        if (events.existsByProviderAndProviderEventId(provider.code(), providerEventId)) {
            return;
        }
        Payment p = payments.findByProviderAndProviderOrderId(provider.code(), providerOrderId)
                .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
        events.save(new PaymentEventRecord(provider.code(), providerEventId, "payment.failed", p.getId()));
        if (p.getStatus() != Payment.Status.CREATED) {
            return;
        }
        p.setStatus(Payment.Status.FAILED);
        p.setFailureReason(reason);
        publisher.publishEvent(new PaymentEvents.PaymentFailed(p.getOrderId(), p.getId(), reason));
    }

    /** Demo only: simulate the provider callback for the order's payment. */
    @Transactional
    public void simulateDemo(Long orderId, boolean success) {
        if (!provider.isDemo()) {
            throw ApiException.notFound("NOT_FOUND", "Not found");
        }
        Payment p = payments.findByOrderId(orderId)
                .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
        String eventId = "demo_evt_" + Hashing.randomCode(14);
        if (success) {
            confirmSuccess(p.getProviderOrderId(), "demo_pay_" + Hashing.randomCode(14), eventId, p.getAmountMinor());
        } else {
            confirmFailure(p.getProviderOrderId(), eventId, "Payment declined (demo)");
        }
    }

    /** Allows a new attempt after a failure by resetting the payment to CREATED with a fresh provider order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentInit retry(Long orderId) {
        Payment p = payments.findByOrderId(orderId)
                .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
        if (p.getStatus() != Payment.Status.FAILED) {
            throw ApiException.conflict("PAYMENT_NOT_RETRYABLE", "This payment cannot be retried");
        }
        p.setStatus(Payment.Status.CREATED);
        p.setFailureReason(null);
        return init(p, Map.of("mode", provider.isDemo() ? "demo" : "live"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelIfPending(Long orderId) {
        payments.findByOrderId(orderId)
                .filter(p -> p.getStatus() == Payment.Status.CREATED || p.getStatus() == Payment.Status.FAILED)
                .ifPresent(p -> p.setStatus(Payment.Status.CANCELLED));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refund(Long orderId, long amountMinor) {
        Payment p = payments.findByOrderId(orderId)
                .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
        if (p.getStatus() != Payment.Status.SUCCEEDED && p.getStatus() != Payment.Status.PARTIALLY_REFUNDED) {
            throw ApiException.conflict("PAYMENT_NOT_REFUNDABLE", "Only captured payments can be refunded");
        }
        if (amountMinor <= 0 || p.getRefundedMinor() + amountMinor > p.getAmountMinor()) {
            throw ApiException.rule("REFUND_AMOUNT_INVALID", "Refund amount exceeds the captured amount");
        }
        if (!COD.equals(p.getProvider())) {
            provider.refund(p, amountMinor);
        }
        p.addRefund(amountMinor);
        p.setStatus(p.getRefundedMinor() == p.getAmountMinor() ? Payment.Status.REFUNDED : Payment.Status.PARTIALLY_REFUNDED);
    }

    @Transactional(readOnly = true)
    public Optional<PaymentView> forOrder(Long orderId) {
        return payments.findByOrderId(orderId).map(p -> new PaymentView(p.getId(), p.getProvider(), p.getProviderOrderId(),
                p.getProviderPaymentId(), p.getStatus().name(), p.getAmountMinor(), p.getRefundedMinor(), p.getCurrency(),
                p.getFailureReason(), p.getCreatedAt()));
    }

    private PaymentInit init(Payment p, Map<String, Object> clientParams) {
        return new PaymentInit(p.getId(), p.getProvider(), provider.isDemo(), p.getProviderOrderId(), p.getAmountMinor(),
                p.getCurrency(), clientParams);
    }
}
