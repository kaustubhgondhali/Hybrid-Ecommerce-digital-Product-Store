package com.hybrid.platform.commerce.settings;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PincodeServiceTest {

    private final SettingsService settingsService = mock(SettingsService.class);
    private final PincodeService service = new PincodeService(settingsService);

    private StoreSettings settings(Storefront sf) {
        StoreSettings s = new StoreSettings(sf, "Store", "INV");
        when(settingsService.entity(sf)).thenReturn(s);
        return s;
    }

    @Test
    void allModeDeliversEverywhere() {
        StoreSettings s = new StoreSettings(Storefront.SHOP, "Store", "INV");
        assertThat(PincodeService.serviceable(s, "110001")).isTrue();
    }

    @Test
    void allowListMatchesByPrefix() {
        StoreSettings s = new StoreSettings(Storefront.SHOP, "Store", "INV");
        s.setPincodeMode("ALLOW");
        s.setPincodeList("4000\n 560001 \n");
        assertThat(PincodeService.serviceable(s, "400012")).isTrue();
        assertThat(PincodeService.serviceable(s, "560001")).isTrue();
        assertThat(PincodeService.serviceable(s, "110001")).isFalse();
        s.setPincodeList(null);
        assertThat(PincodeService.serviceable(s, "400012")).isFalse();
    }

    @Test
    void blockListExcludesMatches() {
        StoreSettings s = new StoreSettings(Storefront.SHOP, "Store", "INV");
        s.setPincodeMode("BLOCK");
        s.setPincodeList("1100");
        assertThat(PincodeService.serviceable(s, "110001")).isFalse();
        assertThat(PincodeService.serviceable(s, "400012")).isTrue();
    }

    @Test
    void codFollowsItsOwnPincodeListOnTheShopOnly() {
        StoreSettings shop = settings(Storefront.SHOP);
        shop.setCodEnabled(true);
        shop.setCodPincodeList("4000");
        assertThat(service.check(Storefront.SHOP, "400012").codAvailable()).isTrue();
        var elsewhere = service.check(Storefront.SHOP, "110001");
        assertThat(elsewhere.serviceable()).isTrue();
        assertThat(elsewhere.codAvailable()).isFalse();
        assertThat(elsewhere.message()).contains("not available");

        StoreSettings digital = settings(Storefront.DIGITAL);
        digital.setCodEnabled(true);
        assertThat(service.check(Storefront.DIGITAL, "400012").codAvailable()).isFalse();
    }

    @Test
    void rejectsMalformedPincodes() {
        settings(Storefront.SHOP);
        assertThatThrownBy(() -> service.check(Storefront.SHOP, "12345")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.check(Storefront.SHOP, "abcdef")).isInstanceOf(ApiException.class);
    }
}
