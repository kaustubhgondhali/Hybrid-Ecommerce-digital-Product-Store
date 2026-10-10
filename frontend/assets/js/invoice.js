/* Printable invoice view shared by the customer account and the admin panel. */
(function () {
  'use strict';
  const { el, mount, money } = UI;

  function row(label, value) {
    return el('div', { class: 'summary-row' }, el('span', { text: label }), el('span', { text: value }));
  }

  function render(target, inv, backHref, backText) {
    document.title = 'Invoice ' + inv.invoiceNumber;
    const s = inv.seller || {};
    const a = inv.shipTo;
    const printBtn = el('button', { class: 'btn', type: 'button', text: 'Print / Save as PDF' });
    printBtn.addEventListener('click', () => window.print());
    mount(target, el('div', { class: 'container' },
      el('div', { class: 'actions mb no-print' }, el('a', { href: backHref, text: '\u2190 ' + (backText || 'Back to order') }),
        el('span', { class: 'spacer' }), printBtn),
      el('div', { class: 'invoice' },
        el('div', { class: 'invoice-head' },
          el('div', null, el('h1', { text: 'Tax Invoice' }), el('div', { text: 'Invoice no. ' + inv.invoiceNumber }),
            el('div', { class: 'muted', text: 'Date: ' + UI.date(inv.issuedAt, false) }),
            el('div', { class: 'muted', text: 'Order: ' + inv.orderNumber })),
          el('div', { class: 'right' }, el('strong', { text: s.legalName || s.storeName || '' }),
            s.address ? el('div', { class: 'small description', text: s.address }) : null,
            s.taxId ? el('div', { class: 'small', text: 'GSTIN / Tax ID: ' + s.taxId }) : null,
            s.supportEmail ? el('div', { class: 'small', text: s.supportEmail }) : null)),
        el('div', { class: 'invoice-parties' },
          el('div', null, el('h3', { text: 'Billed to' }), el('div', { text: inv.buyerName }), el('div', { class: 'muted', text: inv.buyerEmail }),
            inv.buyerGstin ? el('div', { class: 'small', text: 'GSTIN: ' + inv.buyerGstin }) : null,
            inv.placeOfSupply ? el('div', { class: 'small', text: 'Place of supply: ' + inv.placeOfSupply }) : null),
          a ? el('div', null, el('h3', { text: 'Shipped to' }), el('div', { text: a.name }), el('div', { text: a.line1 }),
            a.line2 ? el('div', { text: a.line2 }) : null, el('div', { text: a.city + ', ' + a.state + ' ' + a.postalCode + ', ' + a.country })) : null),
        el('table', null,
          el('thead', null, el('tr', null, el('th', { text: 'Item' }), el('th', { text: 'HSN/SAC' }), el('th', { class: 'num', text: 'Qty' }),
            el('th', { class: 'num', text: 'Unit price' }), el('th', { class: 'num', text: 'Taxable value' }),
            el('th', { class: 'num', text: 'GST' }), el('th', { class: 'num', text: 'Amount' }))),
          el('tbody', null, inv.lines.map((l) => el('tr', null,
            el('td', null, el('div', { text: l.title + (l.variantName && l.variantName !== 'Default' ? ' (' + l.variantName + ')' : '') }),
              l.sku ? el('div', { class: 'muted small', text: 'SKU ' + l.sku }) : null,
              l.discountMinor ? el('div', { class: 'muted small', text: 'Discount ' + money(l.discountMinor, inv.currency) }) : null),
            el('td', { text: l.hsnSac || '' }), el('td', { class: 'num', text: String(l.quantity) }),
            el('td', { class: 'num', text: money(l.unitPriceMinor, inv.currency) }),
            el('td', { class: 'num', text: money(l.taxableMinor || l.lineTotalMinor, inv.currency) }),
            el('td', { class: 'num', text: (l.gstRateBps ? l.gstRateBps / 100 + '% \u00B7 ' : '') + money(l.taxMinor, inv.currency) }),
            el('td', { class: 'num', text: money(l.lineTotalMinor, inv.currency) }))))),
        el('div', { class: 'invoice-totals' },
          row('Subtotal', money(inv.subtotalMinor, inv.currency)),
          inv.discountMinor ? row('Discount' + (inv.couponCode ? ' (' + inv.couponCode + ')' : ''), '\u2212' + money(inv.discountMinor, inv.currency)) : null,
          inv.shippingMinor ? row('Shipping', money(inv.shippingMinor, inv.currency)) : null,
          inv.codFeeMinor ? row('Cash on delivery fee', money(inv.codFeeMinor, inv.currency)) : null,
          taxRows(inv),
          el('div', { class: 'summary-row total' }, el('span', { text: 'Total' }), el('span', { text: money(inv.totalMinor, inv.currency) }))),
        el('p', { class: 'muted small mt', text: (inv.pricesIncludeTax ? 'Prices are inclusive of GST. ' : '')
          + (inv.paymentMethod === 'COD' ? 'Paid by cash on delivery. ' : '') + 'This is a computer-generated invoice.' }))));
  }

  function taxRows(inv) {
    const prefix = inv.pricesIncludeTax ? 'Included ' : '';
    if (inv.cgstMinor || inv.sgstMinor) {
      return [row(prefix + 'CGST', money(inv.cgstMinor, inv.currency)), row(prefix + 'SGST', money(inv.sgstMinor, inv.currency))];
    }
    if (inv.igstMinor) return row(prefix + 'IGST', money(inv.igstMinor, inv.currency));
    return row(prefix + 'Tax', money(inv.taxMinor, inv.currency));
  }

  window.Invoice = { render };
})();
