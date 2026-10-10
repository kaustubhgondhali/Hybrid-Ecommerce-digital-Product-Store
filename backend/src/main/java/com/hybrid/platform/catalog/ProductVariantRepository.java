package com.hybrid.platform.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    @Query("select v from ProductVariant v join fetch v.product where v.id in :ids")
    List<ProductVariant> findWithProductByIdIn(@Param("ids") Collection<Long> ids);

    boolean existsBySkuIgnoreCase(String sku);

    boolean existsBySkuIgnoreCaseAndIdNot(String sku, Long id);
}
