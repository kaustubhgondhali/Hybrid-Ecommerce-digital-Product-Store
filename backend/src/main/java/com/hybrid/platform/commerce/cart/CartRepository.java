package com.hybrid.platform.commerce.cart;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByCustomerIdAndStorefront(Long customerId, Storefront storefront);
}
