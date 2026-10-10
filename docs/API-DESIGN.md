# API Design

Status: **Proposed (pre-implementation)**
This is a design map, not an implemented API. Each service's OpenAPI 3.1 spec in `contracts/openapi/` will become the source of truth; the frontend TypeScript client is generated from those specs.

---

## 1. Conventions

| Topic | Rule |
|---|---|
| Base path | `/api/v1/<service-area>/...` through the gateway |
| Storefront segment | `{storefront}` ∈ `shop` \| `digital` (lowercase in URLs, `SHOP`/`DIGITAL` in payloads) |
| Admin endpoints | `/api/v1/<area>/admin/{storefront}/...` |
| Internal endpoints | `/internal/<area>/...` — reachable only on the internal network, never routed by the gateway |
| Format | JSON, `camelCase` fields, UTF-8 |
| IDs | UUID strings; human numbers (`orderNumber`) are separate fields |
| Money | `{ "amountMinor": 129900, "currency": "INR" }` — never floats, never formatted strings |
| Time | RFC 3339 UTC (`2026-10-05T11:53:00Z`) |
| Errors | RFC 9457 `application/problem+json` with `type`, `title`, `status`, `detail`, `instance`, `code`, `traceId`, optional `errors[]` for field validation |
| Pagination | Cursor-based: `?limit=24&cursor=…` → `{ "items": [], "nextCursor": "…" }`. Admin tables may also use `page/size` |
| Filtering/sorting | Explicit allow-listed query params (`?sort=-publishedAt`); no generic query languages |
| Idempotency | `Idempotency-Key` header **required** on checkout, refunds, manual entitlement grants, publish; replays return the original response; same key + different body → `422` |
| Concurrency | Admin updates use `ETag` (from `version`) + `If-Match`; mismatch → `412` |
| Versioning | URI major version (`/v1`). Additive changes don't bump version |
| Auth | Browser: HttpOnly cookies (gateway maps to bearer). Server-to-server: `Authorization: Bearer` |
| Caching | Public catalog GETs: `Cache-Control: public, s-maxage=60, stale-while-revalidate`; everything authenticated: `private, no-store` |

### Status code usage

`200` read/update, `201` create (+`Location`), `202` accepted async, `204` no content, `400` malformed, `401` unauthenticated, `403` authenticated but not permitted, `404` not found **or not owned** (don't leak existence across customers/storefronts), `409` state conflict (e.g. cancel a shipped order), `412` ETag mismatch, `422` business rule violation (e.g. affiliate product added to cart), `429` rate limited, `503` dependency unavailable.

### Error example

```json
{
  "type": "https://errors.platform.example/checkout/product-not-directly-sellable",
  "title": "Product cannot be purchased on this site",
  "status": 422,
  "code": "CHECKOUT_PRODUCT_NOT_DIRECT",
  "detail": "Variant 0192… belongs to an AFFILIATE product.",
  "traceId": "4bf92f3577b34da6"
}
```

## 2. Authentication surfaces

| Surface | Token audience | Cookie (set by identity via gateway) | Accepted on |
|---|---|---|---|
| Customer | `customer` | `__Host-cust_at` (access, `Path=/`); `__Secure-cust_rt` (refresh, `Path=/api/v1/auth/customer`) | Customer and public routes |
| Shop admin | `shop-admin` | `__Host-shop_adm_at`; `__Secure-shop_adm_rt` (`Path=/api/v1/auth/admin/shop`) | `/api/v1/*/admin/shop/**` only |
| Digital admin | `digital-admin` | `__Host-dig_adm_at`; `__Secure-dig_adm_rt` (`Path=/api/v1/auth/admin/digital`) | `/api/v1/*/admin/digital/**` only |
| Service | `internal` | n/a (client-credentials bearer) | `/internal/**` only |

A token of one audience is rejected on every other surface. Details in [SECURITY.md](./SECURITY.md).

## 3. Gateway routing

| Incoming path | Target | Auth at gateway |
|---|---|---|
| `/api/v1/auth/customer/**` | identity | public (rate-limited) / customer cookie for `me`, `logout` |
| `/api/v1/auth/admin/{storefront}/**` | identity | public for login/MFA (strict rate limit), admin cookie otherwise |
| `/api/v1/identity/admin/{storefront}/**` | identity | admin cookie, storefront match |
| `/api/v1/catalog/{storefront}/**` | catalog | public (optional customer) |
| `/api/v1/catalog/admin/{storefront}/**` | catalog | admin cookie, storefront match |
| `/api/v1/commerce/{storefront}/**` | commerce | customer cookie |
| `/api/v1/commerce/orders/**`, `/addresses/**` | commerce | customer cookie |
| `/api/v1/commerce/admin/{storefront}/**` | commerce | admin cookie, storefront match |
| `/api/v1/commerce/webhooks/**` | commerce | none (signature verified by service) |
| `/api/v1/payments/webhooks/{provider}` | payment | none (signature verified by service); raw body preserved |
| `/api/v1/payments/admin/{storefront}/**` | payment | admin cookie, storefront match |
| `/api/v1/delivery/**` | delivery | customer cookie |
| `/api/v1/delivery/admin/{storefront}/**` | delivery | admin cookie, storefront match |
| `/api/v1/affiliate/postbacks/{networkId}` | affiliate | none (HMAC + optional IP allowlist verified by service) |
| `/api/v1/affiliate/admin/{storefront}/**` | affiliate | admin cookie, storefront match |
| `/api/v1/notifications/admin/{storefront}/**` | notification | admin cookie, storefront match |
| `/go/{code}` | affiliate | public (optional customer), rate-limited |
| `/internal/**` | — | **blocked (404)** |

The gateway's storefront match is a first line of defense; every service re-checks the JWT and storefront claim itself.

## 4. Endpoint map

Legend: **P** public, **C** customer, **A** admin (storefront-scoped, permission noted), **I** internal, **W** webhook.

### 4.1 identity-service

| Method | Path | Who | Purpose |
|---|---|---|---|
| GET | `/api/v1/auth/csrf` | P | Issue CSRF token cookie |
| POST | `/api/v1/auth/customer/register` | P | Create customer (pending verification) |
| POST | `/api/v1/auth/customer/verify-email` | P | Consume verification token |
| POST | `/api/v1/auth/customer/login` | P | Password login → sets customer cookies |
| POST | `/api/v1/auth/customer/refresh` | P (refresh cookie) | Rotate refresh token, new access token |
| POST | `/api/v1/auth/customer/logout` | C | Revoke session |
| POST | `/api/v1/auth/customer/password-reset/request` | P | Always `202` (no account enumeration) |
| POST | `/api/v1/auth/customer/password-reset/confirm` | P | Set new password, revoke all sessions |
| GET | `/api/v1/auth/customer/me` | C | Current customer profile |
| PATCH | `/api/v1/auth/customer/me` | C | Update profile |
| POST | `/api/v1/auth/admin/{storefront}/login` | P | Step 1: password → MFA challenge ID |
| POST | `/api/v1/auth/admin/{storefront}/mfa/verify` | P (challenge) | Step 2: TOTP → sets admin cookies |
| POST | `/api/v1/auth/admin/{storefront}/mfa/enroll` | A (pre-MFA limited token) | First-login TOTP enrollment |
| POST | `/api/v1/auth/admin/{storefront}/refresh` | P (refresh cookie) | Rotate |
| POST | `/api/v1/auth/admin/{storefront}/logout` | A | Revoke session |
| GET | `/api/v1/auth/admin/{storefront}/me` | A | Admin profile + effective permissions (for UI rendering only) |
| GET/POST | `/api/v1/identity/admin/{storefront}/staff` | A `identity:staff:*` | List / invite staff |
| PATCH | `/api/v1/identity/admin/{storefront}/staff/{id}` | A `identity:staff:write` | Change roles, disable |
| POST | `/api/v1/identity/admin/{storefront}/staff/{id}/force-logout` | A `identity:staff:write` | Revoke all sessions |
| GET/POST/PATCH | `/api/v1/identity/admin/{storefront}/roles` | A `identity:role:*` (MASTER_ADMIN) | Custom roles within storefront |
| POST | `/api/v1/identity/invitations/accept` | P (invite token) | Set password, start MFA enrollment |
| GET | `/internal/identity/jwks.json` | I | Public signing keys |
| POST | `/internal/identity/oauth2/token` | I | Client-credentials tokens |

### 4.2 catalog-service

| Method | Path | Who | Purpose |
|---|---|---|---|
| GET | `/api/v1/catalog/{storefront}/products` | P | List/search published products (`q`, `category`, `productType`, `sellingMethod`, `cursor`) |
| GET | `/api/v1/catalog/{storefront}/products/{slug}` | P | Product detail incl. variants, display price or affiliate offer (`/go/{code}` link) |
| GET | `/api/v1/catalog/{storefront}/categories` | P | Category tree |
| GET/POST | `/api/v1/catalog/admin/{storefront}/products` | A `catalog:product:read/write` | List (all statuses) / create draft |
| GET/PATCH | `/api/v1/catalog/admin/{storefront}/products/{id}` | A | Read / update (If-Match) |
| POST | `/api/v1/catalog/admin/{storefront}/products/{id}/publish` | A `catalog:product:publish` | Validate combination rules, publish (Idempotency-Key) |
| POST | `/api/v1/catalog/admin/{storefront}/products/{id}/archive` | A `catalog:product:publish` | Archive |
| POST/PATCH/DELETE | `/api/v1/catalog/admin/{storefront}/products/{id}/variants[/{variantId}]` | A | Variants |
| PUT | `/api/v1/catalog/admin/{storefront}/variants/{variantId}/prices` | A `catalog:price:write` | Set price schedule (DIRECT only) |
| PUT | `/api/v1/catalog/admin/{storefront}/products/{id}/affiliate-offer` | A `catalog:product:write` | Set merchant/destination (AFFILIATE only) |
| POST | `/api/v1/catalog/admin/{storefront}/products/{id}/media/upload-url` | A | Presigned upload to `public-media` |
| CRUD | `/api/v1/catalog/admin/{storefront}/categories` | A `catalog:category:write` | Categories |
| POST | `/internal/catalog/pricing/quote` | I (commerce) | `{storefront, variantIds[]}` → price, currency, status, productType, sellingMethod, title, sku |

### 4.3 commerce-service

| Method | Path | Who | Purpose |
|---|---|---|---|
| GET | `/api/v1/commerce/{storefront}/cart` | C | Cart with server-priced lines |
| POST | `/api/v1/commerce/{storefront}/cart/items` | C | Add `{variantId, quantity}` — `422` if AFFILIATE / unpublished / other storefront |
| PATCH/DELETE | `/api/v1/commerce/{storefront}/cart/items/{itemId}` | C | Change qty / remove |
| GET | `/api/v1/commerce/{storefront}/shipping-options` | C | Options for cart + address (physical lines only) |
| POST | `/api/v1/commerce/{storefront}/checkout/quote` | C | Server-computed totals (no side effects) |
| POST | `/api/v1/commerce/{storefront}/checkout` | C | Place order + payment session (Idempotency-Key) → `{orderId, payment: {provider, clientToken \| redirectUrl}}` |
| GET | `/api/v1/commerce/orders` | C | My orders (both storefronts, filterable) |
| GET | `/api/v1/commerce/orders/{orderId}` | C | Order detail + status (used for post-payment polling) |
| GET | `/api/v1/commerce/orders/{orderId}/invoices/{invoiceId}/download` | C | Short-lived presigned URL to invoice PDF |
| CRUD | `/api/v1/commerce/addresses` | C | Address book |
| GET | `/api/v1/commerce/admin/{storefront}/orders` | A `commerce:order:read` | Search/filter orders |
| GET | `/api/v1/commerce/admin/{storefront}/orders/{id}` | A | Detail + history |
| POST | `/api/v1/commerce/admin/{storefront}/orders/{id}/cancel` | A `commerce:order:cancel` | Cancel (pre-dispatch) → refund + restock |
| POST | `/api/v1/commerce/admin/{storefront}/orders/{id}/refunds` | A `commerce:refund:create` | Full/partial refund (Idempotency-Key) |
| GET | `/api/v1/commerce/admin/{storefront}/inventory` | A `commerce:inventory:read` | Stock levels |
| POST | `/api/v1/commerce/admin/{storefront}/inventory/{variantId}/adjustments` | A `commerce:inventory:write` | Ledgered adjustment `{delta, reason, note}` |
| POST | `/api/v1/commerce/admin/{storefront}/orders/{id}/shipments` | A `commerce:fulfillment:write` | Create shipment for lines |
| PATCH | `/api/v1/commerce/admin/{storefront}/shipments/{id}` | A `commerce:fulfillment:write` | Tracking number / status |
| CRUD | `/api/v1/commerce/admin/{storefront}/shipping-methods` | A `commerce:settings:write` | Rates |
| CRUD | `/api/v1/commerce/admin/{storefront}/tax-rules` | A `commerce:settings:write` | Tax |
| GET | `/api/v1/commerce/admin/{storefront}/invoices` | A `commerce:invoice:read` | Invoices / credit notes |
| POST | `/api/v1/commerce/webhooks/shipping/{carrier}` | W | Carrier tracking updates |

### 4.4 payment-service

| Method | Path | Who | Purpose |
|---|---|---|---|
| POST | `/api/v1/payments/webhooks/{provider}` | W | Verified provider events |
| GET | `/api/v1/payments/admin/{storefront}/payments` | A `payment:read` | Payment records |
| GET | `/api/v1/payments/admin/{storefront}/payments/{id}` | A `payment:read` | Detail incl. refunds, webhook trail |
| POST | `/internal/payments/intents` | I (commerce) | `{orderId, storefront, amountMinor, currency, customerRef}` |
| POST | `/internal/payments/{paymentId}/refunds` | I (commerce) | `{amountMinor, reason, requestedBy}` |
| GET | `/internal/payments/by-order/{orderId}` | I (commerce) | Current state (reconciliation) |

There is intentionally **no** browser-facing "confirm payment" endpoint.

### 4.5 digital-delivery-service

| Method | Path | Who | Purpose |
|---|---|---|---|
| GET | `/api/v1/delivery/library` | C | My entitlements (`?storefront=`) |
| GET | `/api/v1/delivery/entitlements/{id}` | C | Detail, assets available, downloads remaining |
| POST | `/api/v1/delivery/entitlements/{id}/download-links` | C | `{assetId}` → `{url, expiresAt}` (short TTL) |
| GET | `/api/v1/delivery/entitlements/{id}/license-keys` | C | Assigned license keys (decrypted on read, `no-store`) |
| POST | `/api/v1/delivery/admin/{storefront}/assets/upload-sessions` | A `delivery:asset:write` | `{productId, variantId?, filename, sizeBytes, sha256}` → presigned PUT |
| POST | `/api/v1/delivery/admin/{storefront}/assets/{id}/complete` | A `delivery:asset:write` | Trigger verification |
| GET/PATCH | `/api/v1/delivery/admin/{storefront}/assets[/{id}]` | A | List / retire |
| PUT | `/api/v1/delivery/admin/{storefront}/products/{productId}/policy` | A `delivery:policy:write` | Download limit, access duration, version policy |
| POST | `/api/v1/delivery/admin/{storefront}/license-pools/{id}/imports` | A `delivery:license:write` | Bulk import keys |
| GET | `/api/v1/delivery/admin/{storefront}/entitlements` | A `delivery:entitlement:read` | Search |
| POST | `/api/v1/delivery/admin/{storefront}/entitlements` | A `delivery:entitlement:grant` | Manual grant (Idempotency-Key, audited, reason required) |
| POST | `/api/v1/delivery/admin/{storefront}/entitlements/{id}/revoke` | A `delivery:entitlement:revoke` | Revoke (audited) |
| GET | `/internal/delivery/products/{productId}/readiness` | I (catalog) | Publish gate: READY asset or license pool exists |
| POST | `/internal/delivery/availability` | I (commerce) | License availability for variant quantities |

### 4.6 affiliate-service

| Method | Path | Who | Purpose |
|---|---|---|---|
| GET | `/go/{code}` | P | Validate, record click, `302` redirect |
| POST | `/api/v1/affiliate/postbacks/{networkId}` | W | Conversion postback (HMAC-signed) |
| CRUD | `/api/v1/affiliate/admin/{storefront}/networks` | A `affiliate:network:write` | Networks + secrets |
| CRUD | `/api/v1/affiliate/admin/{storefront}/merchants` | A `affiliate:merchant:write` | Merchants + allowed domains |
| GET | `/api/v1/affiliate/admin/{storefront}/reports/clicks` | A `affiliate:report:read` | Aggregated clicks |
| GET | `/api/v1/affiliate/admin/{storefront}/conversions` | A `affiliate:report:read` | Conversions |
| PATCH | `/api/v1/affiliate/admin/{storefront}/conversions/{id}` | A `affiliate:conversion:write` | Approve / reverse / mark paid |
| POST | `/api/v1/affiliate/admin/{storefront}/report-imports` | A `affiliate:conversion:write` | Upload network CSV report |
| POST | `/internal/affiliate/merchants/{merchantId}/validate-destination` | I (catalog) | `{url}` → allowed / reason |

### 4.7 notification-service

| Method | Path | Who | Purpose |
|---|---|---|---|
| CRUD | `/api/v1/notifications/admin/{storefront}/templates` | A `notification:template:write` | Templates per storefront |
| POST | `/api/v1/notifications/admin/{storefront}/templates/{id}/preview` | A | Render with sample data (no send) |
| GET | `/api/v1/notifications/admin/{storefront}/log` | A `notification:log:read` | Delivery log |

## 5. Frontend data access rules

- Server Components / Route Handlers call the gateway over the internal network, forwarding the user's cookies as a bearer token. Client Components call `/api/v1/...` on the same origin (cookies sent automatically, CSRF header added).
- The frontend displays prices and stock **from API responses only** and never sends prices, totals, discounts, stock, or status in request bodies (DTOs don't have those fields).
- Permission data from `/auth/admin/{storefront}/me` is used to hide/show UI only; every action is re-authorized server-side.
