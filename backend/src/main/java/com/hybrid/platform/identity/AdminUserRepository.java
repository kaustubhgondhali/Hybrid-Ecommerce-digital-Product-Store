package com.hybrid.platform.identity;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

    Optional<AdminUser> findByStorefrontAndEmailIgnoreCase(Storefront storefront, String email);

    Optional<AdminUser> findByIdAndStorefront(Long id, Storefront storefront);

    List<AdminUser> findByStorefrontOrderByCreatedAtAsc(Storefront storefront);

    long countByStorefrontAndRoleAndStatus(Storefront storefront, AdminRole role, AdminUser.Status status);
}
