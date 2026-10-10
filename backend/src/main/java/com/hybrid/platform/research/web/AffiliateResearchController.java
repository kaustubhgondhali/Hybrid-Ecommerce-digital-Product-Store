package com.hybrid.platform.research.web;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.research.AffiliateResearchAnalyzer.Report;
import com.hybrid.platform.research.AffiliateResearchService;
import com.hybrid.platform.research.AffiliateResearchService.AnalyzeBody;
import com.hybrid.platform.research.AffiliateResearchService.Meta;
import com.hybrid.platform.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/{storefront}/admin/research")
public class AffiliateResearchController {

    private final AffiliateResearchService research;

    public AffiliateResearchController(AffiliateResearchService research) {
        this.research = research;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_READ')")
    public Meta meta(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return research.meta(sf);
    }

    @PostMapping("/analyze")
    @PreAuthorize("hasAuthority('PERM_AFFILIATE_READ')")
    public Report analyze(@PathVariable String storefront, @RequestBody(required = false) AnalyzeBody body) {
        Storefront sf = Storefront.fromPath(storefront);
        CurrentUser.admin(sf);
        return research.analyze(sf, body == null ? new AnalyzeBody(null, null, null, null) : body);
    }
}
