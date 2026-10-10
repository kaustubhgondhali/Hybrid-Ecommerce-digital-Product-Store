package com.hybrid.platform.commerce.order;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GstCalculatorTest {

    @Test
    void exclusivePricesSameStateSplitIntoCgstAndSgst() {
        var r = GstCalculator.compute(List.of(new GstCalculator.LineIn(100_000, 1, 1800)), 0, 0, false, "27", "27");
        assertThat(r.interState()).isFalse();
        assertThat(r.taxMinor()).isEqualTo(18_000);
        assertThat(r.cgstMinor()).isEqualTo(9_000);
        assertThat(r.sgstMinor()).isEqualTo(9_000);
        assertThat(r.igstMinor()).isZero();
        assertThat(r.totalMinor()).isEqualTo(118_000);
    }

    @Test
    void inclusivePricesOtherStateUseIgstAndKeepTheTotal() {
        var r = GstCalculator.compute(List.of(new GstCalculator.LineIn(118_000, 1, 1800)), 0, 0, true, "27", "29");
        assertThat(r.interState()).isTrue();
        assertThat(r.taxableMinor()).isEqualTo(100_000);
        assertThat(r.igstMinor()).isEqualTo(18_000);
        assertThat(r.cgstMinor() + r.sgstMinor()).isZero();
        assertThat(r.totalMinor()).isEqualTo(118_000);
    }

    @Test
    void discountIsAllocatedProRataAndChargesUseTheHighestRate() {
        var r = GstCalculator.compute(List.of(
                new GstCalculator.LineIn(30_000, 2, 500),
                new GstCalculator.LineIn(40_000, 1, 1800)), 10_000, 5_000, false, "27", "27");
        assertThat(r.lines()).extracting(GstCalculator.LineOut::discountMinor).containsExactly(6_000L, 4_000L);
        assertThat(r.lines()).extracting(GstCalculator.LineOut::taxMinor).containsExactly(2_700L, 6_480L);
        assertThat(r.chargesTaxMinor()).isEqualTo(900);
        assertThat(r.taxableMinor()).isEqualTo(95_000);
        assertThat(r.taxMinor()).isEqualTo(10_080);
        assertThat(r.totalMinor()).isEqualTo(105_080);
    }

    @Test
    void discountNeverExceedsSubtotal() {
        var r = GstCalculator.compute(List.of(new GstCalculator.LineIn(1_000, 1, 1800)), 50_000, 0, false, null, null);
        assertThat(r.discountMinor()).isEqualTo(1_000);
        assertThat(r.totalMinor()).isZero();
    }

    @Test
    void oddTaxPutsTheExtraPaisaInSgstAndUnknownStatesAreIntraState() {
        var r = GstCalculator.compute(List.of(new GstCalculator.LineIn(50_006, 1, 1800)), 0, 0, false, "27", null);
        assertThat(r.interState()).isFalse();
        assertThat(r.taxMinor()).isEqualTo(9_001);
        assertThat(r.cgstMinor()).isEqualTo(4_500);
        assertThat(r.sgstMinor()).isEqualTo(4_501);
    }

    @Test
    void zeroRatedLinesHaveNoTax() {
        var r = GstCalculator.compute(List.of(new GstCalculator.LineIn(25_000, 4, 0)), 0, 0, true, "27", "27");
        assertThat(r.taxMinor()).isZero();
        assertThat(r.totalMinor()).isEqualTo(100_000);
    }
}
