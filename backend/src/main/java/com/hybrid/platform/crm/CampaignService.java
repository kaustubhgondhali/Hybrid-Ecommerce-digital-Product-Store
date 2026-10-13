package com.hybrid.platform.crm;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.commerce.coupon.CouponRepositories;
import com.hybrid.platform.crm.CrmSegments.Segment;
import com.hybrid.platform.security.AdminPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Email campaigns to a CRM segment. Only active customers who opted in to this storefront's marketing receive
 * them; every email has a one-click unsubscribe link. Results count what recipients did in the 7 days after sending.
 */
@Service
public class CampaignService {

    private static final Logger log = LoggerFactory.getLogger(CampaignService.class);
    static final int MAX_RECIPIENTS = 2000;
    static final Duration ATTRIBUTION_WINDOW = Duration.ofDays(7);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public record CampaignInput(String name, String segment, String subject, String body, String couponCode) {
    }

    public record Results(long clicks, long clickers, long conversions, long commissionMinor, long orders, long revenueMinor,
                          long couponOrders, int windowDays, boolean windowOpen) {
    }

    public record CampaignView(Long id, String name, String segment, String segmentLabel, String subject, String body,
                               String couponCode, String status, int recipients, String createdByEmail, Instant createdAt,
                               Instant sentAt, Results results) {
    }

    public record Audience(String segment, String segmentLabel, long inSegment, long willReceive, int maxRecipients,
                           boolean smtpReady, String storeName, String storeUrl) {
    }

    /** Published after the campaign is claimed for sending; delivered on a background thread after commit. */
    public record CampaignQueued(Storefront storefront, Long campaignId) {
    }

    private final CrmRepositories.CampaignRepository campaigns;
    private final CrmService crm;
    private final CrmQueries queries;
    private final MarketingMailer mailer;
    private final CouponRepositories.CouponRepository coupons;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public CampaignService(CrmRepositories.CampaignRepository campaigns, CrmService crm, CrmQueries queries,
                           MarketingMailer mailer, CouponRepositories.CouponRepository coupons, AuditService audit,
                           ApplicationEventPublisher events) {
        this.campaigns = campaigns;
        this.crm = crm;
        this.queries = queries;
        this.mailer = mailer;
        this.coupons = coupons;
        this.audit = audit;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<CampaignView> list(Storefront sf) {
        return campaigns.findByStorefrontOrderByCreatedAtDesc(sf, PageRequest.of(0, 50)).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public CampaignView get(Storefront sf, Long id) {
        return view(find(sf, id));
    }

    @Transactional(readOnly = true)
    public Audience audience(Storefront sf, String segment) {
        Segment s = CrmService.parseSegment(segment, Segment.SUBSCRIBERS);
        long willReceive = Math.min(crm.audience(sf, s).size(), MAX_RECIPIENTS);
        return new Audience(s.name(), s.label(), crm.segmentSize(sf, s), willReceive, MAX_RECIPIENTS, mailer.smtpReady(sf),
                mailer.storeName(sf), mailer.storeUrl(sf));
    }

    @Transactional
    public CampaignView create(AdminPrincipal admin, CampaignInput in) {
        CrmCampaign c = new CrmCampaign(admin.storefront(), admin.email());
        apply(admin.storefront(), c, in);
        c = campaigns.save(c);
        audit.record(admin, "CAMPAIGN_CREATED", "CrmCampaign", c.getId(), c.getName());
        return view(c);
    }

    @Transactional
    public CampaignView update(AdminPrincipal admin, Long id, CampaignInput in) {
        CrmCampaign c = draft(admin.storefront(), id);
        apply(admin.storefront(), c, in);
        return view(campaigns.save(c));
    }

    @Transactional
    public void delete(AdminPrincipal admin, Long id) {
        CrmCampaign c = draft(admin.storefront(), id);
        campaigns.delete(c);
        audit.record(admin, "CAMPAIGN_DELETED", "CrmCampaign", id, c.getName());
    }

    /**
     * Sends one rendered copy to staff (default: the signed-in admin) with a sample name. Deliberately not
     * transactional: queueing the email writes to the outbox, which a read-only transaction would reject.
     */
    public void sendTest(AdminPrincipal admin, Long id, String to) {
        CrmCampaign c = find(admin.storefront(), id);
        String recipient = to == null || to.isBlank() ? admin.email() : to.trim();
        if (!EMAIL.matcher(recipient).matches()) {
            throw ApiException.badRequest("INVALID_EMAIL", "Enter a valid email address");
        }
        requireSmtp(admin.storefront());
        Storefront sf = admin.storefront();
        mailer.sendPreview(sf, recipient, "[Test] " + render(c.getSubject(), "Asha", sf, c.getCouponCode()),
                render(c.getBody(), "Asha", sf, c.getCouponCode()));
    }

    @Transactional
    public CampaignView send(AdminPrincipal admin, Long id) {
        CrmCampaign c = draft(admin.storefront(), id);
        requireSmtp(admin.storefront());
        if (crm.audience(admin.storefront(), c.getSegment()).isEmpty()) {
            throw ApiException.rule("NO_RECIPIENTS", "Nobody in this segment has subscribed to marketing email yet");
        }
        if (campaigns.claimForSending(c.getId(), Instant.now()) != 1) {
            throw ApiException.conflict("CAMPAIGN_ALREADY_SENT", "This campaign is already being sent");
        }
        audit.record(admin, "CAMPAIGN_SENT", "CrmCampaign", c.getId(), c.getName() + " -> " + c.getSegment());
        events.publishEvent(new CampaignQueued(admin.storefront(), c.getId()));
        return view(campaigns.findById(c.getId()).orElseThrow());
    }

    /** Delivers a claimed campaign. Recipients are recorded first, so a restart never emails anyone twice. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void deliver(CampaignQueued e) {
        CrmCampaign c = campaigns.findByIdAndStorefront(e.campaignId(), e.storefront()).orElse(null);
        if (c == null || c.getStatus() != CrmCampaign.Status.SENDING) return;
        Instant now = Instant.now();
        int sent = 0;
        for (CustomerSnapshot r : crm.audience(e.storefront(), c.getSegment())) {
            if (sent >= MAX_RECIPIENTS) break;
            if (!queries.addRecipient(c.getId(), r.id(), now)) continue;
            mailer.send(e.storefront(), r.id(), r.email(), render(c.getSubject(), r.firstName(), e.storefront(), c.getCouponCode()),
                    render(c.getBody(), r.firstName(), e.storefront(), c.getCouponCode()), "CAMPAIGN");
            sent++;
        }
        c.markSent(sent);
        campaigns.save(c);
        log.info("Campaign {} '{}' sent to {} subscriber(s) on {}", c.getId(), c.getName(), sent, e.storefront());
    }

    /** Replaces {name}, {store}, {link} and {coupon}. */
    String render(String template, String firstName, Storefront sf, String coupon) {
        return template.replace("{name}", firstName)
                .replace("{store}", mailer.storeName(sf))
                .replace("{link}", mailer.storeUrl(sf))
                .replace("{coupon}", coupon == null ? "" : coupon);
    }

    // ------------------------------------------------------------------ helpers

    private void apply(Storefront sf, CrmCampaign c, CampaignInput in) {
        if (in == null) throw ApiException.badRequest("CAMPAIGN_INVALID", "Fill in the campaign");
        String name = required(in.name(), 120, "Name");
        String subject = required(MarketingMailer.oneLine(in.subject()), 200, "Subject");
        String body = required(in.body(), 10000, "Message");
        Segment segment = CrmService.parseSegment(in.segment(), Segment.SUBSCRIBERS);
        String coupon = in.couponCode() == null || in.couponCode().isBlank() ? null : in.couponCode().trim().toUpperCase(Locale.ROOT);
        if (coupon != null && coupons.findByStorefrontAndCode(sf, coupon).isEmpty()) {
            throw ApiException.badRequest("COUPON_NOT_FOUND", "There is no coupon " + coupon + " in this store");
        }
        if (coupon == null && body.contains("{coupon}")) {
            throw ApiException.badRequest("COUPON_MISSING", "The message uses {coupon}, so choose a coupon");
        }
        c.edit(name, segment, subject, body, coupon);
    }

    private static String required(String value, int max, String label) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty() || v.length() > max) {
            throw ApiException.badRequest("CAMPAIGN_INVALID", label + " must be 1 to " + max + " characters");
        }
        return v;
    }

    private void requireSmtp(Storefront sf) {
        if (!mailer.smtpReady(sf)) {
            throw ApiException.rule("SMTP_NOT_READY", "Set up and enable email in Settings → Email (SMTP) first");
        }
    }

    private CrmCampaign find(Storefront sf, Long id) {
        return campaigns.findByIdAndStorefront(id, sf)
                .orElseThrow(() -> ApiException.notFound("CAMPAIGN_NOT_FOUND", "Campaign not found"));
    }

    private CrmCampaign draft(Storefront sf, Long id) {
        CrmCampaign c = find(sf, id);
        if (c.getStatus() != CrmCampaign.Status.DRAFT) {
            throw ApiException.conflict("CAMPAIGN_NOT_DRAFT", "This campaign has already been sent and can no longer be changed");
        }
        return c;
    }

    private CampaignView view(CrmCampaign c) {
        Results results = null;
        if (c.getSentAt() != null) {
            Instant end = c.getSentAt().plus(ATTRIBUTION_WINDOW);
            CrmQueries.CampaignResults r = queries.campaignResults(c.getStorefront(), c.getId(), c.getSentAt(), end, c.getCouponCode());
            results = new Results(r.clicks(), r.clickers(), r.conversions(), r.commissionMinor(), r.orders(), r.revenueMinor(),
                    r.couponOrders(), (int) ATTRIBUTION_WINDOW.toDays(), Instant.now().isBefore(end));
        }
        return new CampaignView(c.getId(), c.getName(), c.getSegment().name(), c.getSegment().label(), c.getSubject(), c.getBody(),
                c.getCouponCode(), c.getStatus().name(), c.getRecipients(), c.getCreatedByEmail(), c.getCreatedAt(), c.getSentAt(), results);
    }
}
