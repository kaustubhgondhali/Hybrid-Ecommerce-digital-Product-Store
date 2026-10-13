package com.hybrid.platform.crm;

import com.hybrid.platform.common.Storefront;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public final class CrmRepositories {

    private CrmRepositories() {
    }

    public interface ConsentRepository extends JpaRepository<MarketingConsent, MarketingConsent.Key> {
        List<MarketingConsent> findByCustomerId(Long customerId);

        long countByStorefrontAndSubscribedTrue(Storefront storefront);
    }

    public interface NoteRepository extends JpaRepository<CrmNote, Long> {
        List<CrmNote> findByStorefrontAndCustomerIdOrderByCreatedAtDesc(Storefront storefront, Long customerId);

        Optional<CrmNote> findByIdAndStorefront(Long id, Storefront storefront);
    }

    public interface TaskRepository extends JpaRepository<CrmTask, Long> {
        Optional<CrmTask> findByIdAndStorefront(Long id, Storefront storefront);

        List<CrmTask> findByStorefrontAndCustomerIdOrderByCreatedAtDesc(Storefront storefront, Long customerId);

        @Query("select t from CrmTask t where t.storefront = :sf and t.doneAt is null order by case when t.dueOn is null then 1 else 0 end, t.dueOn, t.createdAt")
        List<CrmTask> open(@Param("sf") Storefront storefront, Pageable page);

        @Query("select t from CrmTask t where t.storefront = :sf and t.doneAt is not null order by t.doneAt desc")
        List<CrmTask> recentlyDone(@Param("sf") Storefront storefront, Pageable page);

        long countByStorefrontAndDoneAtIsNull(Storefront storefront);
    }

    public interface CampaignRepository extends JpaRepository<CrmCampaign, Long> {
        List<CrmCampaign> findByStorefrontOrderByCreatedAtDesc(Storefront storefront, Pageable page);

        Optional<CrmCampaign> findByIdAndStorefront(Long id, Storefront storefront);

        /** Moves a draft to SENDING exactly once, so a double click cannot send a campaign twice. */
        @Modifying(flushAutomatically = true, clearAutomatically = true)
        @Query("update CrmCampaign c set c.status = com.hybrid.platform.crm.CrmCampaign.Status.SENDING, c.updatedAt = :now "
                + "where c.id = :id and c.status = com.hybrid.platform.crm.CrmCampaign.Status.DRAFT")
        int claimForSending(@Param("id") Long id, @Param("now") java.time.Instant now);
    }

    public interface AutomationSettingsRepository extends JpaRepository<CrmAutomationSettings, Storefront> {
    }
}
