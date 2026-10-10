package com.hybrid.platform.research;

import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.research.AffiliateResearchAnalyzer.CatalogSnapshot;
import com.hybrid.platform.research.AffiliateResearchAnalyzer.Report;
import com.hybrid.platform.research.AffiliateResearchAnalyzer.Request;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AffiliateResearchAnalyzerTest {

    @Test
    void shopNotesBoostElectronicsAndReturnGoogleSearches() {
        Report r = AffiliateResearchAnalyzer.analyze(
                new Request(Storefront.SHOP, "wireless earbuds",
                        "Google reviews say these ANC earbuds are worth it under 3000. Battery life is excellent. "
                                + "Some buyers returned a flimsy case. Best noise cancelling headphones India 2026.",
                        List.of(), 4),
                new CatalogSnapshot(List.of("Demo Desk Lamp"), List.of("Gadgets"), List.of("amazon.in")));

        assertThat(r.opportunityScore()).isGreaterThanOrEqualTo(55);
        assertThat(r.categories()).isNotEmpty();
        assertThat(r.categories().getFirst().categoryId()).isIn("electronics", "fitness", "home-kitchen");
        assertThat(r.platforms()).extracting(p -> p.id()).contains("amazon-in", "flipkart");
        assertThat(r.platforms()).allMatch(p -> p.program() != null && !p.program().isBlank());
        assertThat(r.nextSearches()).isNotEmpty();
        assertThat(r.nextSearches().getFirst().googleUrl()).startsWith("https://www.google.com/search?q=");
        assertThat(r.signals()).anyMatch(s -> "KEYWORD".equals(s.type()) || "PRAISE".equals(s.type()));
        assertThat(r.analyzedInMs()).isLessThan(500);
        assertThat(r.actions()).isNotEmpty();
    }

    @Test
    void digitalStorefrontOnlySuggestsAffiliateDigitalCategories() {
        Report r = AffiliateResearchAnalyzer.analyze(
                new Request(Storefront.DIGITAL, "Python course",
                        "Best Python course for beginners review. Udemy coupon. Career switch worth it.",
                        List.of("courses"), 3),
                new CatalogSnapshot(List.of(), List.of(), List.of()));

        assertThat(r.categories()).hasSize(1);
        assertThat(r.categories().getFirst().categoryId()).isEqualTo("courses");
        assertThat(r.platforms()).extracting(p -> p.id()).contains("udemy");
        assertThat(r.platforms()).noneMatch(p -> p.id().equals("flipkart"));
    }

    @Test
    void marksSuggestionsAlreadyInCatalog() {
        Report r = AffiliateResearchAnalyzer.analyze(
                new Request(Storefront.SHOP, "air fryer", "best air fryer India review compact",
                        List.of("home-kitchen"), 4),
                new CatalogSnapshot(List.of("Compact Air Fryer Black"), List.of("Kitchen"), List.of()));

        assertThat(r.categories()).isNotEmpty();
        assertThat(r.categories().getFirst().products())
                .anyMatch(p -> p.title().toLowerCase().contains("air fryer") && p.alreadyInCatalog());
    }

    @Test
    void platformListHasNoDiscontinuedProgramsAndMatchesConnectedHostsExactly() {
        Report shop = AffiliateResearchAnalyzer.analyze(new Request(Storefront.SHOP, "", "", List.of(), 2),
                new CatalogSnapshot(List.of(), List.of(), List.of("www.amazon.in", "notflipkart.example")));
        Report digital = AffiliateResearchAnalyzer.analyze(new Request(Storefront.DIGITAL, "", "", List.of(), 2),
                new CatalogSnapshot(List.of(), List.of(), List.of()));

        assertThat(shop.platforms()).extracting(p -> p.id()).doesNotContain("shareasale", "appstore");
        assertThat(digital.platforms()).extracting(p -> p.id()).doesNotContain("shareasale", "appstore").contains("awin");
        assertThat(shop.platforms()).filteredOn(p -> p.id().equals("flipkart"))
                .singleElement().satisfies(p -> {
                    assertThat(p.access()).isEqualTo("VIA_NETWORK");
                    assertThat(p.merchantConnected()).isFalse();
                });
        assertThat(shop.platforms()).filteredOn(p -> p.id().equals("amazon-in"))
                .singleElement().satisfies(p -> assertThat(p.merchantConnected()).isTrue());
        assertThat(shop.platforms()).allMatch(p -> p.requirement() != null && !p.requirement().isBlank());
    }

    @Test
    void emptyInputStillReturnsPlaybookFast() {
        Report r = AffiliateResearchAnalyzer.analyze(
                new Request(Storefront.SHOP, "", "", List.of(), 2),
                new CatalogSnapshot(List.of(), List.of(), List.of()));
        assertThat(r.categories()).isNotEmpty();
        assertThat(r.platforms()).isNotEmpty();
        assertThat(r.analyzedInMs()).isLessThan(200);
        assertThat(r.grade()).isIn("Strong", "Good", "Fair", "Thin");
    }
}
