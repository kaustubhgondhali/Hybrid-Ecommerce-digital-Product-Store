package com.hybrid.platform.affiliate;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class AffiliateRepositories {

    private AffiliateRepositories() {
    }

    public interface MerchantRepository extends JpaRepository<AffiliateMerchant, Long> {
        List<AffiliateMerchant> findByStorefrontOrderByNameAsc(Storefront storefront);

        Optional<AffiliateMerchant> findByStorefrontAndDomain(Storefront storefront, String domain);

        Optional<AffiliateMerchant> findByIdAndStorefront(Long id, Storefront storefront);
    }

    public interface LinkRepository extends JpaRepository<AffiliateLink, Long> {
        Optional<AffiliateLink> findByProductId(Long productId);

        List<AffiliateLink> findByProductIdIn(Collection<Long> productIds);

        Optional<AffiliateLink> findByCode(String code);

        boolean existsByCode(String code);

        List<AffiliateLink> findByStorefrontOrderByUpdatedAtDesc(Storefront storefront);
    }

    public interface ClickRepository extends JpaRepository<AffiliateClick, Long> {

        @Query("select c.linkId, count(c) from AffiliateClick c where c.storefront = :sf and c.createdAt >= :since "
                + "and c.suspectedBot = false group by c.linkId")
        List<Object[]> countByLinkSince(@Param("sf") Storefront sf, @Param("since") Instant since);

        @Query("select count(c) from AffiliateClick c where c.storefront = :sf and c.createdAt >= :since and c.suspectedBot = false")
        long countSince(@Param("sf") Storefront sf, @Param("since") Instant since);

        Optional<AffiliateClick> findByClickRef(String clickRef);
    }

    public interface ConversionRepository extends JpaRepository<AffiliateConversion, Long> {
        Page<AffiliateConversion> findByStorefrontOrderByReportedAtDesc(Storefront storefront, Pageable pageable);

        Optional<AffiliateConversion> findByStorefrontAndExternalTxnId(Storefront storefront, String externalTxnId);

        Optional<AffiliateConversion> findByIdAndStorefront(Long id, Storefront storefront);

        @Query("select c.status, count(c), coalesce(sum(c.commissionMinor), 0) from AffiliateConversion c "
                + "where c.storefront = :sf group by c.status")
        List<Object[]> summary(@Param("sf") Storefront sf);

        /** Per link: conversions (not reversed) and commission for clicks made since {@code since}. */
        @Query("select k.linkId, count(c), coalesce(sum(c.commissionMinor), 0) from AffiliateConversion c, AffiliateClick k "
                + "where c.clickRef = k.clickRef and c.storefront = :sf and k.storefront = :sf "
                + "and k.createdAt >= :since and c.status <> :excluded group by k.linkId")
        List<Object[]> byLinkForClicksSince(@Param("sf") Storefront sf, @Param("since") Instant since,
                                            @Param("excluded") AffiliateConversion.Status excluded);
    }
}
