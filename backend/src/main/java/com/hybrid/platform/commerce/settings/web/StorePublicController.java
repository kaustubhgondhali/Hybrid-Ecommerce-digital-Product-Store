package com.hybrid.platform.commerce.settings.web;

import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.commerce.order.IndianStates;
import com.hybrid.platform.commerce.settings.PincodeService;
import com.hybrid.platform.commerce.settings.SettingsService;
import com.hybrid.platform.content.ContentService;
import com.hybrid.platform.payment.PaymentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class StorePublicController {

    public record StoreInfo(SettingsService.PublicStoreInfo store, boolean demoPayments, boolean affiliateOnly,
                            List<ContentService.FooterLink> pages) {
    }

    public record State(String code, String name) {
    }

    private final SettingsService settings;
    private final PaymentService payments;
    private final ContentService content;
    private final PincodeService pincodes;
    private final AppProperties props;

    public StorePublicController(SettingsService settings, PaymentService payments, ContentService content,
                                 PincodeService pincodes, AppProperties props) {
        this.settings = settings;
        this.payments = payments;
        this.content = content;
        this.pincodes = pincodes;
        this.props = props;
    }

    @GetMapping("/api/{storefront}/catalog/store")
    public StoreInfo store(@PathVariable String storefront) {
        Storefront sf = Storefront.fromPath(storefront);
        SettingsService.PublicStoreInfo info = settings.publicInfo(sf, props.currency());
        return new StoreInfo(info, payments.isDemo(), info.affiliateOnly(), content.footer(sf));
    }

    @GetMapping("/api/{storefront}/catalog/states")
    public List<State> states(@PathVariable String storefront) {
        Storefront.fromPath(storefront);
        return IndianStates.BY_CODE.entrySet().stream().map(e -> new State(e.getKey(), e.getValue()))
                .sorted(java.util.Comparator.comparing(State::name)).toList();
    }

    @GetMapping("/api/{storefront}/catalog/pincode/{pin}")
    public PincodeService.Check pincode(@PathVariable String storefront, @PathVariable String pin) {
        return pincodes.check(Storefront.fromPath(storefront), pin);
    }
}
