package com.hybrid.platform.commerce.order;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class OrderRepositories {

    private OrderRepositories() {
    }

    public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {
        Optional<CustomerOrder> findByOrderNumber(String orderNumber);

        Optional<CustomerOrder> findByIdAndStorefront(Long id, Storefront storefront);

        Optional<CustomerOrder> findByCustomerIdAndIdempotencyKey(Long customerId, String idempotencyKey);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select o from CustomerOrder o where o.id = :id")
        Optional<CustomerOrder> lockById(@Param("id") Long id);

        Page<CustomerOrder> findByCustomerIdOrderByPlacedAtDesc(Long customerId, Pageable pageable);

        Page<CustomerOrder> findByCustomerIdAndStorefrontOrderByPlacedAtDesc(Long customerId, Storefront storefront, Pageable pageable);

        @Query("""
                select o from CustomerOrder o
                where o.storefront = :sf
                  and (:status is null or o.status = :status)
                  and (:q is null or lower(o.orderNumber) like lower(concat('%', :q, '%'))
                       or lower(o.customerEmail) like lower(concat('%', :q, '%'))
                       or lower(o.customerName) like lower(concat('%', :q, '%')))
                order by o.placedAt desc
                """)
        Page<CustomerOrder> search(@Param("sf") Storefront sf, @Param("status") CustomerOrder.Status status,
                                   @Param("q") String q, Pageable pageable);

        @Query("select o.id from CustomerOrder o where o.status = :status and o.expiresAt < :now order by o.id")
        List<Long> findIdsByStatusExpiringBefore(@Param("status") CustomerOrder.Status status, @Param("now") Instant now,
                                                 Pageable pageable);

        long countByStorefrontAndStatus(Storefront sf, CustomerOrder.Status status);

        long countByStorefrontAndPlacedAtAfter(Storefront sf, Instant after);

        @Query("""
                select coalesce(sum(o.totalMinor), 0) from CustomerOrder o
                where o.storefront = :sf and o.paidAt >= :after and o.status in :statuses
                """)
        long sumPaidSince(@Param("sf") Storefront sf, @Param("after") Instant after,
                          @Param("statuses") Collection<CustomerOrder.Status> statuses);

        @Query("""
                select count(o) from CustomerOrder o
                where o.storefront = :sf and o.paidAt >= :after and o.status in :statuses
                """)
        long countPaidSince(@Param("sf") Storefront sf, @Param("after") Instant after,
                            @Param("statuses") Collection<CustomerOrder.Status> statuses);

        List<CustomerOrder> findTop8ByStorefrontOrderByPlacedAtDesc(Storefront sf);

        @Query("""
                select count(l) > 0 from OrderLine l join l.order o
                where o.customerId = :customerId and l.productId = :productId and o.status in :statuses
                """)
        boolean hasPurchased(@Param("customerId") Long customerId, @Param("productId") Long productId,
                             @Param("statuses") Collection<CustomerOrder.Status> statuses);
    }

    public interface ShipmentRepository extends JpaRepository<Shipment, Long> {
        List<Shipment> findByOrderIdOrderByCreatedAtAsc(Long orderId);
    }

    public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
        Optional<Invoice> findByOrderId(Long orderId);
    }

    /** Native access to invoice_sequences (composite key); the row lock makes numbering gapless per storefront/year. */
    public interface InvoiceSequenceRepository extends JpaRepository<Invoice, Long> {
        @Query(value = "select next_value from invoice_sequences where storefront = :sf and year_value = :year for update",
                nativeQuery = true)
        Optional<Long> lockNext(@Param("sf") String sf, @Param("year") int year);

        @Modifying(flushAutomatically = true)
        @Query(value = "insert into invoice_sequences (storefront, year_value, next_value) values (:sf, :year, 1)",
                nativeQuery = true)
        int insertSequence(@Param("sf") String sf, @Param("year") int year);

        @Modifying(flushAutomatically = true)
        @Query(value = "update invoice_sequences set next_value = next_value + 1 where storefront = :sf and year_value = :year",
                nativeQuery = true)
        int increment(@Param("sf") String sf, @Param("year") int year);
    }
}
