package com.hybrid.platform.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public final class PaymentRepositories {

    private PaymentRepositories() {
    }

    public interface PaymentRepository extends JpaRepository<Payment, Long> {
        Optional<Payment> findByOrderId(Long orderId);

        Optional<Payment> findByProviderAndProviderOrderId(String provider, String providerOrderId);
    }

    public interface PaymentEventRepository extends JpaRepository<PaymentEventRecord, Long> {
        boolean existsByProviderAndProviderEventId(String provider, String providerEventId);
    }
}
