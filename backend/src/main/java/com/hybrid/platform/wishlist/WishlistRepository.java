package com.hybrid.platform.wishlist;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WishlistRepository extends JpaRepository<WishlistItem, Long> {

    List<WishlistItem> findByCustomerIdAndStorefrontOrderByCreatedAtDesc(Long customerId, Storefront storefront);

    boolean existsByCustomerIdAndProductId(Long customerId, Long productId);

    long countByCustomerIdAndStorefront(Long customerId, Storefront storefront);

    @Modifying
    @Query("delete from WishlistItem w where w.customerId = :customerId and w.productId = :productId")
    int deleteItem(@Param("customerId") Long customerId, @Param("productId") Long productId);
}
