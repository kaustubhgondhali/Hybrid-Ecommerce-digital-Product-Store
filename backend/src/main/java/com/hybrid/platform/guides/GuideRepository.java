package com.hybrid.platform.guides;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GuideRepository extends JpaRepository<Guide, Long> {

    List<Guide> findByStorefrontOrderByUpdatedAtDesc(Storefront storefront);

    List<Guide> findByStorefrontAndStatusOrderByPublishedAtDesc(Storefront storefront, Guide.Status status);

    List<Guide> findByStatus(Guide.Status status);

    Optional<Guide> findByIdAndStorefront(Long id, Storefront storefront);

    Optional<Guide> findByStorefrontAndSlug(Storefront storefront, String slug);

    @Query("select distinct g from Guide g join g.productIds pid where g.storefront = :sf and g.status = :status and pid = :productId "
            + "order by g.publishedAt desc")
    List<Guide> findFeaturing(@Param("sf") Storefront sf, @Param("status") Guide.Status status, @Param("productId") Long productId);
}
