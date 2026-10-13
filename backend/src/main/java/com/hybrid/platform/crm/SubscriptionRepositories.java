package com.hybrid.platform.crm;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public final class SubscriptionRepositories {

    private SubscriptionRepositories() {
    }

    public interface PriceAlertRepository extends JpaRepository<PriceAlert, Long> {
        Optional<PriceAlert> findByProductIdAndEmail(Long productId, String email);

        Optional<PriceAlert> findByTokenHash(String tokenHash);

        List<PriceAlert> findByStorefrontAndConfirmedAtIsNotNullAndNotifiedAtIsNull(Storefront storefront);

        List<PriceAlert> findByProductIdAndConfirmedAtIsNotNullAndNotifiedAtIsNull(Long productId);

        long countByStorefrontAndConfirmedAtIsNotNullAndNotifiedAtIsNull(Storefront storefront);
    }

    public interface NewsletterRepository extends JpaRepository<NewsletterSubscriber, Long> {
        Optional<NewsletterSubscriber> findByStorefrontAndEmail(Storefront storefront, String email);

        Optional<NewsletterSubscriber> findByTokenHash(String tokenHash);

        List<NewsletterSubscriber> findByStorefrontAndConfirmedAtIsNotNullAndUnsubscribedAtIsNull(Storefront storefront);

        long countByStorefrontAndConfirmedAtIsNotNullAndUnsubscribedAtIsNull(Storefront storefront);
    }
}
