package com.hybrid.platform.crm.web;

import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.crm.CampaignService;
import com.hybrid.platform.crm.CrmAutomationService;
import com.hybrid.platform.crm.CrmService;
import com.hybrid.platform.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/{storefront}/admin/crm")
public class CrmAdminController {

    public record NoteRequest(String body) {
    }

    public record TagsRequest(List<String> tags) {
    }

    public record TestRequest(String to) {
    }

    private final CrmService crm;
    private final CampaignService campaigns;
    private final CrmAutomationService automations;

    public CrmAdminController(CrmService crm, CampaignService campaigns, CrmAutomationService automations) {
        this.crm = crm;
        this.campaigns = campaigns;
        this.automations = automations;
    }

    // ------------------------------------------------------------------ customers

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('PERM_CRM_READ')")
    public CrmService.Overview overview(@PathVariable String storefront) {
        return crm.overview(sf(storefront));
    }

    @GetMapping("/segments")
    @PreAuthorize("hasAuthority('PERM_CRM_READ')")
    public List<CrmService.SegmentInfo> segments(@PathVariable String storefront) {
        sf(storefront);
        return crm.segmentDefinitions();
    }

    @GetMapping("/customers")
    @PreAuthorize("hasAuthority('PERM_CRM_READ')")
    public PageResponse<CrmService.CustomerRow> customers(@PathVariable String storefront,
                                                          @RequestParam(required = false) String segment,
                                                          @RequestParam(required = false) String q,
                                                          @RequestParam(required = false) String tag,
                                                          @RequestParam(required = false) String sort,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "25") int size) {
        return crm.list(sf(storefront), segment, q, tag, sort, page, size);
    }

    @GetMapping("/customers/{id}")
    @PreAuthorize("hasAuthority('PERM_CRM_READ')")
    public CrmService.CustomerDetail customer(@PathVariable String storefront, @PathVariable Long id) {
        return crm.detail(CurrentUser.admin(Storefront.fromPath(storefront)), id);
    }

    @PostMapping("/customers/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CRM_WRITE')")
    public CrmService.NoteView addNote(@PathVariable String storefront, @PathVariable Long id, @RequestBody NoteRequest req) {
        return crm.addNote(CurrentUser.admin(Storefront.fromPath(storefront)), id, req.body());
    }

    @DeleteMapping("/notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_CRM_WRITE')")
    public void deleteNote(@PathVariable String storefront, @PathVariable Long noteId) {
        crm.deleteNote(CurrentUser.admin(Storefront.fromPath(storefront)), noteId);
    }

    @PutMapping("/customers/{id}/tags")
    @PreAuthorize("hasAuthority('PERM_CRM_WRITE')")
    public List<String> setTags(@PathVariable String storefront, @PathVariable Long id, @RequestBody TagsRequest req) {
        return crm.setTags(CurrentUser.admin(Storefront.fromPath(storefront)), id, req.tags());
    }

    @GetMapping("/tags")
    @PreAuthorize("hasAuthority('PERM_CRM_READ')")
    public Map<String, Long> tags(@PathVariable String storefront) {
        return crm.tagCounts(sf(storefront));
    }

    // ------------------------------------------------------------------ tasks

    @GetMapping("/tasks")
    @PreAuthorize("hasAuthority('PERM_CRM_READ')")
    public List<CrmService.TaskView> tasks(@PathVariable String storefront, @RequestParam(defaultValue = "false") boolean done) {
        return crm.listTasks(sf(storefront), done);
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CRM_WRITE')")
    public CrmService.TaskView createTask(@PathVariable String storefront, @RequestBody CrmService.TaskInput in) {
        return crm.createTask(CurrentUser.admin(Storefront.fromPath(storefront)), in);
    }

    @PatchMapping("/tasks/{id}")
    @PreAuthorize("hasAuthority('PERM_CRM_WRITE')")
    public CrmService.TaskView updateTask(@PathVariable String storefront, @PathVariable Long id, @RequestBody CrmService.TaskInput in) {
        return crm.updateTask(CurrentUser.admin(Storefront.fromPath(storefront)), id, in);
    }

    @DeleteMapping("/tasks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_CRM_WRITE')")
    public void deleteTask(@PathVariable String storefront, @PathVariable Long id) {
        crm.deleteTask(CurrentUser.admin(Storefront.fromPath(storefront)), id);
    }

    // ------------------------------------------------------------------ campaigns

    @GetMapping("/campaigns")
    @PreAuthorize("hasAuthority('PERM_CRM_READ')")
    public List<CampaignService.CampaignView> campaigns(@PathVariable String storefront) {
        return campaigns.list(sf(storefront));
    }

    @GetMapping("/campaigns/audience")
    @PreAuthorize("hasAuthority('PERM_CRM_READ')")
    public CampaignService.Audience audience(@PathVariable String storefront, @RequestParam(required = false) String segment) {
        return campaigns.audience(sf(storefront), segment);
    }

    @GetMapping("/campaigns/{id}")
    @PreAuthorize("hasAuthority('PERM_CRM_READ')")
    public CampaignService.CampaignView campaign(@PathVariable String storefront, @PathVariable Long id) {
        return campaigns.get(sf(storefront), id);
    }

    @PostMapping("/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_MARKETING_SEND')")
    public CampaignService.CampaignView createCampaign(@PathVariable String storefront, @RequestBody CampaignService.CampaignInput in) {
        return campaigns.create(CurrentUser.admin(Storefront.fromPath(storefront)), in);
    }

    @PutMapping("/campaigns/{id}")
    @PreAuthorize("hasAuthority('PERM_MARKETING_SEND')")
    public CampaignService.CampaignView updateCampaign(@PathVariable String storefront, @PathVariable Long id,
                                                       @RequestBody CampaignService.CampaignInput in) {
        return campaigns.update(CurrentUser.admin(Storefront.fromPath(storefront)), id, in);
    }

    @DeleteMapping("/campaigns/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_MARKETING_SEND')")
    public void deleteCampaign(@PathVariable String storefront, @PathVariable Long id) {
        campaigns.delete(CurrentUser.admin(Storefront.fromPath(storefront)), id);
    }

    @PostMapping("/campaigns/{id}/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('PERM_MARKETING_SEND')")
    public void testCampaign(@PathVariable String storefront, @PathVariable Long id, @RequestBody(required = false) TestRequest req) {
        campaigns.sendTest(CurrentUser.admin(Storefront.fromPath(storefront)), id, req == null ? null : req.to());
    }

    @PostMapping("/campaigns/{id}/send")
    @PreAuthorize("hasAuthority('PERM_MARKETING_SEND')")
    public CampaignService.CampaignView sendCampaign(@PathVariable String storefront, @PathVariable Long id) {
        return campaigns.send(CurrentUser.admin(Storefront.fromPath(storefront)), id);
    }

    // ------------------------------------------------------------------ automations

    @GetMapping("/automations")
    @PreAuthorize("hasAuthority('PERM_CRM_READ')")
    public CrmAutomationService.AutomationView automations(@PathVariable String storefront) {
        return automations.get(sf(storefront));
    }

    @PutMapping("/automations")
    @PreAuthorize("hasAuthority('PERM_MARKETING_SEND')")
    public CrmAutomationService.AutomationView updateAutomations(@PathVariable String storefront,
                                                                 @RequestBody CrmAutomationService.AutomationInput in) {
        return automations.update(CurrentUser.admin(Storefront.fromPath(storefront)), in);
    }

    @PostMapping("/automations/telegram-test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_MARKETING_SEND')")
    public void telegramTest(@PathVariable String storefront) {
        automations.telegramTest(CurrentUser.admin(Storefront.fromPath(storefront)));
    }

    @PostMapping("/automations/link-check")
    @PreAuthorize("hasAuthority('PERM_MARKETING_SEND')")
    public com.hybrid.platform.affiliate.LinkHealthService.CheckResult checkLinks(@PathVariable String storefront) {
        return automations.checkLinksNow(CurrentUser.admin(Storefront.fromPath(storefront)));
    }

    private static Storefront sf(String path) {
        Storefront sf = Storefront.fromPath(path);
        CurrentUser.admin(sf);
        return sf;
    }
}
