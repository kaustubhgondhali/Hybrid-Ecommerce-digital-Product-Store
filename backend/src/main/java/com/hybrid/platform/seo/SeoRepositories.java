package com.hybrid.platform.seo;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class SeoRepositories {

    private SeoRepositories() {
    }

    public interface SeoRedirectRepository extends JpaRepository<SeoRedirect, Long> {

        List<SeoRedirect> findByStorefrontOrderByCreatedAtDesc(Storefront storefront);

        Optional<SeoRedirect> findByFromPath(String fromPath);

        Optional<SeoRedirect> findByIdAndStorefront(Long id, Storefront storefront);

        List<SeoRedirect> findByStorefrontAndToPath(Storefront storefront, String toPath);

        @Modifying
        @Query("update SeoRedirect r set r.hits = r.hits + :hits, r.lastHitAt = :at where r.id = :id")
        void addHits(@Param("id") Long id, @Param("hits") long hits, @Param("at") Instant at);
    }

    public interface SeoNotFoundRepository extends JpaRepository<SeoNotFound, Long> {

        List<SeoNotFound> findByStorefrontOrderByHitsDesc(Storefront storefront, Pageable pageable);

        Optional<SeoNotFound> findByIdAndStorefront(Long id, Storefront storefront);

        long countByStorefront(Storefront storefront);

        @Modifying
        @Query("update SeoNotFound n set n.hits = n.hits + :hits, n.lastSeen = :at where n.path = :path")
        int addHits(@Param("path") String path, @Param("hits") long hits, @Param("at") Instant at);

        @Modifying
        @Query(value = "insert into seo_not_found (storefront, path, hits, first_seen, last_seen) values (:sf, :path, :hits, :at, :at)",
                nativeQuery = true)
        void insert(@Param("sf") String storefront, @Param("path") String path, @Param("hits") long hits,
                    @Param("at") Instant at);

        @Modifying
        @Query("delete from SeoNotFound n where n.storefront = :sf and n.path = :path")
        void deleteByStorefrontAndPath(@Param("sf") Storefront storefront, @Param("path") String path);

        @Modifying
        @Query("delete from SeoNotFound n where n.storefront = :sf")
        void deleteByStorefront(@Param("sf") Storefront storefront);
    }
}
