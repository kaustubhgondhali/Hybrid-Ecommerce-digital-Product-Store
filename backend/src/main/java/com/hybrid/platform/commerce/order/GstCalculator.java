package com.hybrid.platform.commerce.order;

import java.util.ArrayList;
import java.util.List;

/**
 * Indian GST arithmetic in paise. Supports tax-inclusive (MRP style) and tax-exclusive prices,
 * order-level discounts allocated pro rata to lines, and the CGST+SGST (intra-state) vs IGST (inter-state) split.
 * Shipping and COD charges are taxed at the highest line rate (charges follow the principal supply).
 */
public final class GstCalculator {

    public record LineIn(long unitPriceMinor, int quantity, int rateBps) {
    }

    public record LineOut(long grossMinor, long discountMinor, long taxableMinor, long taxMinor, int rateBps) {
    }

    public record Result(List<LineOut> lines, long subtotalMinor, long discountMinor, long chargesMinor,
                         long chargesTaxMinor, long taxableMinor, long taxMinor, long cgstMinor, long sgstMinor,
                         long igstMinor, long totalMinor, boolean interState) {
    }

    private GstCalculator() {
    }

    /**
     * @param chargesMinor shipping + COD fee, in the same inclusive/exclusive basis as prices
     * @param sellerState  2-digit GST state code of the seller, may be null
     * @param buyerState   2-digit GST state code of the place of supply, may be null (treated as intra-state)
     */
    public static Result compute(List<LineIn> in, long discountMinor, long chargesMinor, boolean pricesIncludeTax,
                                 String sellerState, String buyerState) {
        long subtotal = 0;
        for (LineIn l : in) subtotal += l.unitPriceMinor() * l.quantity();
        long discount = Math.max(0, Math.min(discountMinor, subtotal));

        List<LineOut> out = new ArrayList<>();
        long allocated = 0, taxable = 0, tax = 0;
        int maxRate = 0;
        for (int i = 0; i < in.size(); i++) {
            LineIn l = in.get(i);
            long gross = l.unitPriceMinor() * l.quantity();
            long lineDiscount = i == in.size() - 1 ? discount - allocated
                    : subtotal == 0 ? 0 : Math.round(discount * (double) gross / subtotal);
            allocated += lineDiscount;
            long net = gross - lineDiscount;
            long[] split = split(net, l.rateBps(), pricesIncludeTax);
            out.add(new LineOut(gross, lineDiscount, split[0], split[1], l.rateBps()));
            taxable += split[0];
            tax += split[1];
            maxRate = Math.max(maxRate, l.rateBps());
        }
        long[] charges = split(Math.max(0, chargesMinor), maxRate, pricesIncludeTax);
        taxable += charges[0];
        tax += charges[1];

        boolean inter = sellerState != null && buyerState != null && !sellerState.equals(buyerState);
        long cgst = inter ? 0 : tax / 2;
        long sgst = inter ? 0 : tax - cgst;
        long igst = inter ? tax : 0;
        long total = taxable + tax;
        return new Result(out, subtotal, discount, Math.max(0, chargesMinor), charges[1], taxable, tax, cgst, sgst,
                igst, total, inter);
    }

    /** Returns {taxable, tax} for an amount at a rate. */
    static long[] split(long amount, int rateBps, boolean inclusive) {
        if (rateBps <= 0 || amount <= 0) return new long[]{amount, 0};
        if (inclusive) {
            long taxable = Math.round(amount * 10_000d / (10_000 + rateBps));
            return new long[]{taxable, amount - taxable};
        }
        return new long[]{amount, Math.round(amount * rateBps / 10_000d)};
    }
}
