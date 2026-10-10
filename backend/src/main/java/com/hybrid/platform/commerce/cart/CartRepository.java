package com.hybrid.platform.commerce.cart;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByCustomerIdAndStorefront(Long customerId, Storefront storefront);

    interface SavedBy {
        String getEmail();

        String getFullName();
    }

    /** Active customers who currently have the product in their cart on this storefront. */
    @Query(value = """
            select distinct cu.email as email, cu.full_name as fullName
            from carts ca
              join cart_items ci on ci.cart_id = ca.id
              join customers cu on cu.id = ca.customer_id
            where ca.storefront = :sf and ci.product_id = :productId and cu.status = 'ACTIVE'
            """, nativeQuery = true)
    List<SavedBy> customersWithProduct(@Param("sf") String storefront, @Param("productId") Long productId);
}
