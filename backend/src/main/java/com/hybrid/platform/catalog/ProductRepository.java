package com.hybrid.platform.catalog;

import com.hybrid.platform.catalog.CatalogEnums.ProductStatus;
import com.hybrid.platform.catalog.CatalogEnums.ProductType;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.common.Storefront;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByIdAndStorefront(Long id, Storefront storefront);

    Optional<Product> findByStorefrontAndSlugAndStatus(Storefront storefront, String slug, ProductStatus status);

    boolean existsByStorefrontAndSlug(Storefront storefront, String slug);

    boolean existsByCategoryId(Long categoryId);

    long countByStorefrontAndStatus(Storefront storefront, ProductStatus status);

    /** Product counts per category, so storefront menus only list categories that have something in them. */
    @Query("select p.categoryId, count(p) from Product p where p.storefront = :sf and p.status = :status "
            + "and p.categoryId is not null and (:method is null or p.sellingMethod = :method) group by p.categoryId")
    java.util.List<Object[]> countByCategory(@Param("sf") Storefront sf, @Param("status") ProductStatus status,
                                             @Param("method") SellingMethod method);

    java.util.List<Product> findByStorefrontAndStatusOrderByPublishedAtDesc(Storefront storefront, ProductStatus status);

    java.util.List<Product> findByStorefrontAndSlugIn(Storefront storefront, java.util.Collection<String> slugs);

    boolean existsByStorefrontAndTitleIgnoreCase(Storefront storefront, String title);

    java.util.List<Product> findByStorefrontAndStatusNotOrderByUpdatedAtDesc(Storefront storefront, ProductStatus status);

    Optional<Product> findByStorefrontAndSlug(Storefront storefront, String slug);

    @Query("""
            select p from Product p
            where p.storefront = :sf
              and (:status is null or p.status = :status)
              and (:q is null or lower(p.title) like :q or lower(p.summary) like :q or lower(p.brand) like :q)
              and (:categoryId is null or p.categoryId = :categoryId)
              and (:type is null or p.productType = :type)
              and (:method is null or p.sellingMethod = :method)
            """)
    Page<Product> search(@Param("sf") Storefront sf,
                         @Param("status") ProductStatus status,
                         @Param("q") String q,
                         @Param("categoryId") Long categoryId,
                         @Param("type") ProductType type,
                         @Param("method") SellingMethod method,
                         Pageable pageable);
}
