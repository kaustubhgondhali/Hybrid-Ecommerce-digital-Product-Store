package com.hybrid.platform.offers;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoreOfferRepository extends JpaRepository<StoreOffer, Long> {

    List<StoreOffer> findByStorefrontOrderByCreatedAtDesc(Storefront storefront);

    List<StoreOffer> findByStorefrontAndActiveTrueOrderByCreatedAtDesc(Storefront storefront);

    Optional<StoreOffer> findByIdAndStorefront(Long id, Storefront storefront);

    @Modifying
    @Query("update StoreOffer o set o.clicks = o.clicks + 1 where o.id = :id")
    void countClick(@Param("id") Long id);
}
