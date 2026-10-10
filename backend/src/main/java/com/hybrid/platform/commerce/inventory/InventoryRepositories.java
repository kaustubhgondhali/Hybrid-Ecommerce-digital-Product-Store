package com.hybrid.platform.commerce.inventory;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public final class InventoryRepositories {

    private InventoryRepositories() {
    }

    public interface ItemRepository extends JpaRepository<InventoryItem, Long> {

        List<InventoryItem> findByStorefront(Storefront storefront);

        @Query("select count(i) from InventoryItem i where i.storefront = :sf and (i.onHand - i.reserved) <= i.lowStockThreshold")
        long countLowStock(@Param("sf") Storefront sf);

        /** Atomic reservation: succeeds only if enough unreserved stock exists. */
        @Modifying(flushAutomatically = true)
        @Query("update InventoryItem i set i.reserved = i.reserved + :qty, i.version = i.version + 1 "
                + "where i.variantId = :variantId and i.onHand - i.reserved >= :qty")
        int tryReserve(@Param("variantId") Long variantId, @Param("qty") int qty);

        @Modifying(flushAutomatically = true)
        @Query("update InventoryItem i set i.reserved = i.reserved - :qty, i.version = i.version + 1 "
                + "where i.variantId = :variantId and i.reserved >= :qty")
        int release(@Param("variantId") Long variantId, @Param("qty") int qty);

        @Modifying(flushAutomatically = true)
        @Query("update InventoryItem i set i.reserved = i.reserved - :qty, i.onHand = i.onHand - :qty, "
                + "i.version = i.version + 1 where i.variantId = :variantId and i.reserved >= :qty and i.onHand >= :qty")
        int commit(@Param("variantId") Long variantId, @Param("qty") int qty);

        @Modifying(flushAutomatically = true)
        @Query("update InventoryItem i set i.onHand = i.onHand + :qty, i.version = i.version + 1 where i.variantId = :variantId")
        int restock(@Param("variantId") Long variantId, @Param("qty") int qty);
    }

    public interface ReservationRepository extends JpaRepository<InventoryReservation, Long> {
        List<InventoryReservation> findByOrderIdAndStatus(Long orderId, InventoryReservation.Status status);
    }

    public interface MovementRepository extends JpaRepository<InventoryMovement, Long> {
        List<InventoryMovement> findByVariantIdOrderByCreatedAtDesc(Long variantId, Pageable pageable);
    }
}
