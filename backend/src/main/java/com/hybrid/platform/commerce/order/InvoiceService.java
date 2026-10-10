package com.hybrid.platform.commerce.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.platform.commerce.order.OrderRepositories.InvoiceRepository;
import com.hybrid.platform.commerce.order.OrderRepositories.InvoiceSequenceRepository;
import com.hybrid.platform.commerce.settings.SettingsService;
import com.hybrid.platform.commerce.settings.StoreSettings;
import com.hybrid.platform.common.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Service
public class InvoiceService {

    public record SellerSnapshot(String storeName, String legalName, String address, String taxId, String supportEmail,
                                 int taxRateBps, String stateCode) {
    }

    public record InvoiceLine(String title, String variantName, String sku, int quantity, long unitPriceMinor, long lineTotalMinor,
                              String hsnSac, int gstRateBps, long discountMinor, long taxableMinor, long taxMinor) {
    }

    public record InvoiceView(String invoiceNumber, Instant issuedAt, String orderNumber, Instant placedAt,
                              SellerSnapshot seller, String buyerName, String buyerEmail, ShippingAddress shipTo,
                              List<InvoiceLine> lines, String currency, long subtotalMinor, long shippingMinor,
                              long taxMinor, long totalMinor, String status, long discountMinor, String couponCode,
                              long codFeeMinor, long cgstMinor, long sgstMinor, long igstMinor, boolean pricesIncludeTax,
                              String buyerGstin, String placeOfSupply, String sellerState, String paymentMethod) {
    }

    private final InvoiceRepository invoices;
    private final InvoiceSequenceRepository sequences;
    private final SettingsService settings;
    private final ObjectMapper json;

    public InvoiceService(InvoiceRepository invoices, InvoiceSequenceRepository sequences, SettingsService settings,
                          ObjectMapper json) {
        this.invoices = invoices;
        this.sequences = sequences;
        this.settings = settings;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Invoice issue(CustomerOrder order) {
        Optional<Invoice> existing = invoices.findByOrderId(order.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        StoreSettings s = settings.entity(order.getStorefront());
        int year = Instant.now().atZone(ZoneOffset.UTC).getYear();
        String sf = order.getStorefront().name();
        long next = sequences.lockNext(sf, year).orElseGet(() -> {
            sequences.insertSequence(sf, year);
            return sequences.lockNext(sf, year).orElseThrow();
        });
        sequences.increment(sf, year);
        String number = "%s-%d-%06d".formatted(s.getInvoicePrefix(), year, next);
        SellerSnapshot snapshot = new SellerSnapshot(s.getStoreName(), s.getSellerLegalName(), s.getSellerAddress(),
                s.getSellerTaxId(), s.getSupportEmail(), s.getTaxRateBps(), s.getSellerStateCode());
        try {
            return invoices.save(new Invoice(order.getStorefront(), number, order.getId(), json.writeValueAsString(snapshot)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise seller snapshot", e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<String> numberFor(Long orderId) {
        return invoices.findByOrderId(orderId).map(Invoice::getInvoiceNumber);
    }

    @Transactional(readOnly = true)
    public InvoiceView view(CustomerOrder order) {
        Invoice invoice = invoices.findByOrderId(order.getId())
                .orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "An invoice is issued once payment is received"));
        SellerSnapshot seller;
        try {
            seller = json.readValue(invoice.getSellerSnapshot(), SellerSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt invoice snapshot " + invoice.getId(), e);
        }
        List<InvoiceLine> lines = order.getLines().stream()
                .map(l -> new InvoiceLine(l.getTitle(), l.getVariantName(), l.getSku(), l.getQuantity(), l.getUnitPriceMinor(),
                        l.getLineTotalMinor(), l.getHsnSac(), l.getGstRateBps(), l.getDiscountMinor(), l.getTaxableMinor(),
                        l.getTaxMinor()))
                .toList();
        String place = order.getBuyerStateCode() == null ? null
                : IndianStates.name(order.getBuyerStateCode()) + " (" + order.getBuyerStateCode() + ")";
        String sellerState = seller.stateCode() == null ? null
                : IndianStates.name(seller.stateCode()) + " (" + seller.stateCode() + ")";
        return new InvoiceView(invoice.getInvoiceNumber(), invoice.getIssuedAt(), order.getOrderNumber(), order.getPlacedAt(),
                seller, order.getCustomerName(), order.getCustomerEmail(),
                order.isRequiresShipping() ? order.getShippingAddress() : null, lines, order.getCurrency(),
                order.getSubtotalMinor(), order.getShippingMinor(), order.getTaxMinor(), order.getTotalMinor(),
                order.getStatus().name(), order.getDiscountMinor(), order.getCouponCode(), order.getCodFeeMinor(),
                order.getCgstMinor(), order.getSgstMinor(), order.getIgstMinor(), order.isPricesIncludeTax(),
                order.getBuyerGstin(), place, sellerState, order.getPaymentMethod().name());
    }
}
