package com.hybrid.platform.studio;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public final class StudioRepositories {

    private StudioRepositories() {
    }

    public interface SettingsRepository extends JpaRepository<StudioSettings, Storefront> {
    }

    public interface LogRepository extends JpaRepository<GeneratedContent, Long> {
        List<GeneratedContent> findByStorefrontOrderByCreatedAtDesc(Storefront storefront, Pageable page);

        boolean existsByStorefrontAndProductIdAndKindAndAutomaticTrue(Storefront storefront, Long productId, GeneratedContent.Kind kind);
    }
}
