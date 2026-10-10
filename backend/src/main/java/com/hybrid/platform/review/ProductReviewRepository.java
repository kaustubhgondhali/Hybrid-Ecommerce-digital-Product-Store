package com.hybrid.platform.review;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductReviewRepository extends JpaRepository<ProductReview, Long> {

    interface RatingRow {
        Long getProductId();
        Double getAverage();
        Long getCount();
    }

    Optional<ProductReview> findByProductIdAndCustomerId(Long productId, Long customerId);

    Optional<ProductReview> findByIdAndStorefront(Long id, Storefront storefront);

    Page<ProductReview> findByProductIdAndStatusOrderByCreatedAtDesc(Long productId, ProductReview.Status status,
                                                                    Pageable pageable);

    Page<ProductReview> findByStorefrontAndStatusOrderByCreatedAtDesc(Storefront storefront, ProductReview.Status status,
                                                                     Pageable pageable);

    Page<ProductReview> findByStorefrontOrderByCreatedAtDesc(Storefront storefront, Pageable pageable);

    long countByStorefrontAndStatus(Storefront storefront, ProductReview.Status status);

    @Query("""
            select r.productId as productId, avg(r.rating) as average, count(r) as count
            from ProductReview r where r.productId in :ids and r.status = :status group by r.productId
            """)
    List<RatingRow> ratings(@Param("ids") Collection<Long> productIds, @Param("status") ProductReview.Status status);
}
