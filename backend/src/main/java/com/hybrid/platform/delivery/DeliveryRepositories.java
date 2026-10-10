package com.hybrid.platform.delivery;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public final class DeliveryRepositories {

    private DeliveryRepositories() {
    }

    public interface AssetRepository extends JpaRepository<DigitalAsset, Long> {
        List<DigitalAsset> findByProductIdOrderByCreatedAtDesc(Long productId);

        List<DigitalAsset> findByProductIdAndStatusOrderByCreatedAtDesc(Long productId, DigitalAsset.Status status);

        boolean existsByProductIdAndStatus(Long productId, DigitalAsset.Status status);

        Optional<DigitalAsset> findByIdAndStorefront(Long id, Storefront storefront);
    }

    public interface PolicyRepository extends JpaRepository<DeliveryPolicy, Long> {
    }

    public interface LicenseKeyRepository extends JpaRepository<LicenseKey, Long> {
        long countByProductIdAndStatus(Long productId, LicenseKey.Status status);

        boolean existsByProductIdAndFingerprint(Long productId, String fingerprint);

        List<LicenseKey> findByEntitlementId(Long entitlementId);

        @Query(value = "select * from license_keys where product_id = :productId and status = 'AVAILABLE' "
                + "order by id limit :limit for update", nativeQuery = true)
        List<LicenseKey> lockAvailable(@Param("productId") Long productId, @Param("limit") int limit);
    }

    public interface EntitlementRepository extends JpaRepository<Entitlement, Long> {
        List<Entitlement> findByCustomerIdOrderByGrantedAtDesc(Long customerId);

        Optional<Entitlement> findByIdAndCustomerId(Long id, Long customerId);

        Optional<Entitlement> findByIdAndStorefront(Long id, Storefront storefront);

        boolean existsByOrderLineId(Long orderLineId);

        List<Entitlement> findByOrderId(Long orderId);

        List<Entitlement> findByOrderIdAndCustomerId(Long orderId, Long customerId);

        Page<Entitlement> findByStorefrontOrderByGrantedAtDesc(Storefront storefront, Pageable pageable);

        Page<Entitlement> findByStorefrontAndCustomerIdOrderByGrantedAtDesc(Storefront storefront, Long customerId, Pageable pageable);
    }

    public interface DownloadTokenRepository extends JpaRepository<DownloadToken, Long> {
        Optional<DownloadToken> findByTokenHash(String tokenHash);
    }
}
