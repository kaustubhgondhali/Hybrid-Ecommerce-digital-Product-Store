package com.hybrid.platform.common.audit;

import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.security.AdminPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuditService {

    public record AuditEntry(Long id, String adminEmail, String action, String entityType, String entityId,
                             String details, Instant createdAt) {
    }

    private final AdminAuditLogRepository repository;

    public AuditService(AdminAuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AdminPrincipal admin, String action, String entityType, Object entityId, String details) {
        repository.save(new AdminAuditLog(admin.storefront(), admin.id(), admin.email(), action, entityType,
                entityId == null ? null : String.valueOf(entityId), details));
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEntry> list(Storefront storefront, int page, int size) {
        return PageResponse.of(repository.findByStorefrontOrderByCreatedAtDesc(storefront,
                        PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))),
                a -> new AuditEntry(a.getId(), a.getAdminEmail(), a.getAction(), a.getEntityType(), a.getEntityId(),
                        a.getDetails(), a.getCreatedAt()));
    }
}
