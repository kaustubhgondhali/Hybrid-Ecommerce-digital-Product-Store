package com.hybrid.platform.commerce.settings;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Delivery and cash-on-delivery availability by Indian PIN code, from the admin's allow/block lists. */
@Service
public class PincodeService {

    public record Check(String pincode, boolean serviceable, boolean codAvailable, String estimate, String message) {
    }

    private final SettingsService settings;

    public PincodeService(SettingsService settings) {
        this.settings = settings;
    }

    @Transactional
    public Check check(Storefront sf, String pin) {
        if (!SettingsService.isPin(pin)) {
            throw ApiException.badRequest("INVALID_PINCODE", "Enter a valid 6-digit PIN code");
        }
        StoreSettings s = settings.entity(sf);
        boolean serviceable = serviceable(s, pin);
        boolean cod = serviceable && s.isCodEnabled() && sf == Storefront.SHOP
                && (s.getCodPincodeList() == null || matches(s.getCodPincodeList(), pin));
        String message = !serviceable ? "Sorry, we do not deliver to " + pin + " yet."
                : "Delivery available to " + pin + (s.getDeliveryEstimate() != null ? " \u2014 " + s.getDeliveryEstimate() : "") + "."
                + (s.isCodEnabled() && sf == Storefront.SHOP ? (cod ? " Cash on delivery available." : " Cash on delivery is not available here.") : "");
        return new Check(pin, serviceable, cod, serviceable ? s.getDeliveryEstimate() : null, message);
    }

    static boolean serviceable(StoreSettings s, String pin) {
        return switch (s.getPincodeMode()) {
            case "ALLOW" -> s.getPincodeList() != null && matches(s.getPincodeList(), pin);
            case "BLOCK" -> s.getPincodeList() == null || !matches(s.getPincodeList(), pin);
            default -> true;
        };
    }

    static boolean matches(String list, String pin) {
        for (String entry : list.split("\n")) {
            String e = entry.trim();
            if (!e.isEmpty() && pin.startsWith(e)) return true;
        }
        return false;
    }
}
