package com.hybrid.platform.commerce.settings;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public final class SettingsRepositories {

    private SettingsRepositories() {
    }

    public interface StoreSettingsRepository extends JpaRepository<StoreSettings, Storefront> {
    }

    public interface ShippingMethodRepository extends JpaRepository<ShippingMethod, Long> {
        List<ShippingMethod> findByStorefrontOrderBySortOrderAscIdAsc(Storefront storefront);

        List<ShippingMethod> findByStorefrontAndActiveTrueOrderBySortOrderAscIdAsc(Storefront storefront);

        Optional<ShippingMethod> findByIdAndStorefront(Long id, Storefront storefront);
    }
}
