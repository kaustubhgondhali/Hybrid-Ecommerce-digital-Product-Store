package com.hybrid.platform.notification;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public final class NotificationRepositories {

    private NotificationRepositories() {
    }

    public interface SmtpSettingsRepository extends JpaRepository<SmtpSettings, Storefront> {
    }

    public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
        Page<NotificationLog> findByStorefrontOrderByCreatedAtDesc(Storefront storefront, Pageable pageable);

        @Query("""
                select n.id from NotificationLog n
                where n.status = com.hybrid.platform.notification.NotificationLog.Status.PENDING and n.nextAttemptAt <= :now
                order by n.nextAttemptAt asc
                """)
        List<Long> dueIds(@Param("now") Instant now, Pageable pageable);

        /** Claims a due row by pushing its next attempt out, so two dispatchers never send the same email. */
        @Transactional
        @Modifying
        @Query("""
                update NotificationLog n set n.nextAttemptAt = :lease
                where n.id = :id and n.status = com.hybrid.platform.notification.NotificationLog.Status.PENDING
                  and n.nextAttemptAt <= :now
                """)
        int claim(@Param("id") Long id, @Param("now") Instant now, @Param("lease") Instant lease);

        @Transactional
        @Modifying
        @Query("""
                update NotificationLog n set n.body = null
                where n.body is not null and n.status = com.hybrid.platform.notification.NotificationLog.Status.FAILED
                  and n.createdAt < :before
                """)
        int purgeFailedBodies(@Param("before") Instant before);

        long countByStatus(NotificationLog.Status status);

        boolean existsByStorefrontAndRecipientAndKindAndSubjectAndCreatedAtAfter(Storefront storefront, String recipient,
                                                                                   String kind, String subject, Instant after);

        long countByStorefrontAndStatus(Storefront storefront, NotificationLog.Status status);
    }
}
