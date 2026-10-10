package com.hybrid.platform.catalog;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByStorefrontOrderBySortOrderAscNameAsc(Storefront storefront);

    List<Category> findByStorefrontAndActiveTrueOrderBySortOrderAscNameAsc(Storefront storefront);

    Optional<Category> findByIdAndStorefront(Long id, Storefront storefront);

    Optional<Category> findByStorefrontAndSlug(Storefront storefront, String slug);

    boolean existsByStorefrontAndSlug(Storefront storefront, String slug);
}
