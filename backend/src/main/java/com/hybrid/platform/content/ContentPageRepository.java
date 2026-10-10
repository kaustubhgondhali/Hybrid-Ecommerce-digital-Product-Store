package com.hybrid.platform.content;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContentPageRepository extends JpaRepository<ContentPage, Long> {

    List<ContentPage> findByStorefrontOrderBySortOrderAscTitleAsc(Storefront storefront);

    List<ContentPage> findByStorefrontAndPublishedTrueOrderBySortOrderAscTitleAsc(Storefront storefront);

    Optional<ContentPage> findByStorefrontAndSlug(Storefront storefront, String slug);

    Optional<ContentPage> findByIdAndStorefront(Long id, Storefront storefront);

    boolean existsByStorefrontAndSlug(Storefront storefront, String slug);

    long countByStorefront(Storefront storefront);
}
