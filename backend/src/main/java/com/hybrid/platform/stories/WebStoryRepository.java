package com.hybrid.platform.stories;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebStoryRepository extends JpaRepository<WebStory, Long> {

    List<WebStory> findByStorefrontOrderByUpdatedAtDesc(Storefront storefront);

    Optional<WebStory> findByIdAndStorefront(Long id, Storefront storefront);

    Optional<WebStory> findByStorefrontAndSlug(Storefront storefront, String slug);

    boolean existsByStorefrontAndSlug(Storefront storefront, String slug);

    List<WebStory> findByStorefrontAndStatusOrderByPublishedAtDesc(Storefront storefront, WebStory.Status status, Pageable pageable);

    List<WebStory> findByStatus(WebStory.Status status);
}
