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

    interface SavedBy {
        String getEmail();

        String getFullName();
    }

    /** Active customers who saved the product to their wishlist on this storefront. */
    @Query(value = """
            select distinct cu.email as email, cu.full_name as fullName
            from wishlist_items w join customers cu on cu.id = w.customer_id
            where w.storefront = :sf and w.product_id = :productId and cu.status = 'ACTIVE'
            """, nativeQuery = true)
    List<SavedBy> customersWithProduct(@Param("sf") String storefront, @Param("productId") Long productId);
}
