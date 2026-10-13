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
        List<AffiliateLink> findByProductIdOrderByIdAsc(Long productId);

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

        /** Products ranked by real (non-bot) partner clicks since {@code since}. */
        @Query("select c.productId, count(c) from AffiliateClick c where c.storefront = :sf and c.createdAt >= :since "
                + "and c.suspectedBot = false group by c.productId order by count(c) desc")
        List<Object[]> topProductsSince(@Param("sf") Storefront sf, @Param("since") Instant since, Pageable page);

        @Query("select count(c) from AffiliateClick c where c.productId = :productId and c.createdAt >= :since "
                + "and c.suspectedBot = false")
        long countForProductSince(@Param("productId") Long productId, @Param("since") Instant since);
    }

    public interface PricePointRepository extends JpaRepository<AffiliatePricePoint, Long> {
        List<AffiliatePricePoint> findByProductIdInAndRecordedAtAfterOrderByRecordedAtAsc(Collection<Long> productIds, Instant since);

        /** Full history of every link that changed price since {@code since}, oldest first, to find the drops. */
        @Query("select p from AffiliatePricePoint p where p.linkId in (select q.linkId from AffiliatePricePoint q "
                + "where q.storefront = :sf and q.recordedAt >= :since) order by p.linkId, p.recordedAt, p.id")
        List<AffiliatePricePoint> historyOfLinksChangedSince(@Param("sf") Storefront sf, @Param("since") Instant since);
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
