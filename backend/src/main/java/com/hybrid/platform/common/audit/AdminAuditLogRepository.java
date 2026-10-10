package com.hybrid.platform.common.audit;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {

    Page<AdminAuditLog> findByStorefrontOrderByCreatedAtDesc(Storefront storefront, Pageable pageable);
}
