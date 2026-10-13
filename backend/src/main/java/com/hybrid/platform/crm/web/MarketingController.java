package com.hybrid.platform.crm.web;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.crm.ConsentService;
import com.hybrid.platform.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Customers' own marketing email preferences, and the public one-click unsubscribe used by email links. */
@RestController
@RequestMapping("/api/marketing")
public class MarketingController {

    public record UnsubscribeRequest(Long c, String s, String t) {
    }

    private final ConsentService consents;

    public MarketingController(ConsentService consents) {
        this.consents = consents;
    }

    @GetMapping("/preferences")
    public Map<String, Boolean> preferences() {
        return consents.preferences(CurrentUser.customer().id());
    }

    @PutMapping("/preferences")
    public Map<String, Boolean> updatePreferences(@RequestBody Map<String, Boolean> changes) {
        return consents.updatePreferences(CurrentUser.customer().id(), changes);
    }

    /** Public: the signed token in the email link proves the request is for this customer. */
    @PostMapping("/unsubscribe")
    public Map<String, Object> unsubscribe(@RequestBody UnsubscribeRequest req) {
        Storefront sf = consents.unsubscribe(req.c(), req.s(), req.t());
        return Map.of("unsubscribed", true, "storefront", sf.path());
    }
}
