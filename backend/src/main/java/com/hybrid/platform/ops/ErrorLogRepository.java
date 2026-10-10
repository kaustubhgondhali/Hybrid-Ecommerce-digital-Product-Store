package com.hybrid.platform.ops;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ErrorLogRepository extends JpaRepository<ErrorLogEntry, Long> {

    @Modifying
    @Query("""
            update ErrorLogEntry e set e.occurrences = e.occurrences + 1, e.lastSeen = :now, e.resolved = false,
                   e.message = :message
            where e.fingerprint = :fingerprint
            """)
    int bump(@Param("fingerprint") String fingerprint, @Param("now") Instant now, @Param("message") String message);

    List<ErrorLogEntry> findByResolvedOrderByLastSeenDesc(boolean resolved, Pageable pageable);

    long countByResolved(boolean resolved);

    @Modifying
    @Query("delete from ErrorLogEntry e where e.resolved = true and e.lastSeen < :before")
    int deleteResolvedBefore(@Param("before") Instant before);
}
