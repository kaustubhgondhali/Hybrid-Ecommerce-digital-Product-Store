package com.hybrid.platform.commerce.coupon;

import com.hybrid.platform.common.Storefront;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class CouponRepositories {

    private CouponRepositories() {
    }

    public interface CouponRepository extends JpaRepository<Coupon, Long> {

        Optional<Coupon> findByStorefrontAndCode(Storefront storefront, String code);

        Optional<Coupon> findByIdAndStorefront(Long id, Storefront storefront);

        List<Coupon> findByStorefrontOrderByCreatedAtDesc(Storefront storefront);

        boolean existsByStorefrontAndCode(Storefront storefront, String code);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select c from Coupon c where c.id = :id")
        Optional<Coupon> lockById(@Param("id") Long id);
    }

    public interface RedemptionRepository extends JpaRepository<CouponRedemption, Long> {

        Optional<CouponRedemption> findByOrderId(Long orderId);

        long countByCouponIdAndStatusIn(Long couponId, Collection<CouponRedemption.Status> statuses);

        long countByCouponIdAndCustomerIdAndStatusIn(Long couponId, Long customerId, Collection<CouponRedemption.Status> statuses);
    }
}
