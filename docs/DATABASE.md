# Database Design

> **Superseded by [ADR-001](ADR-001-stack.md):** a single MySQL 8 database; the actual schema is
> `backend/src/main/resources/db/migration/V1__initial_schema.sql`.

Status: **Proposed (pre-implementation)**
Engine: PostgreSQL. Migrations: Flyway, owned by each service.

The tables below are **outlines** to fix ownership, key columns, and invariants. Final DDL is written per service during implementation.

---

## 1. Strategy

### 1.1 Database per service

| Service | Database | DB role (owner) | Runtime role |
|---|---|---|---|
| identity-service | `identity_db` | `identity_owner` | `identity_app` |
| catalog-service | `catalog_db` | `catalog_owner` | `catalog_app` |
| commerce-service | `commerce_db` | `commerce_owner` | `commerce_app` |
| payment-service | `payment_db` | `payment_owner` | `payment_app` |
| digital-delivery-service | `delivery_db` | `delivery_owner` | `delivery_app` |
| affiliate-service | `affiliate_db` | `affiliate_owner` | `affiliate_app` |
| notification-service | `notification_db` | `notification_owner` | `notification_app` |

- **Initial deployment:** one PostgreSQL cluster hosting seven databases. Each service connects with its own credentials and has `CONNECT` on its own database only. Cross-database access is impossible by grants, not just by convention.
- **Owner vs runtime role:** Flyway migrates with the owner role; the application runs with a role that has DML only (no DDL). In compose/dev both may be the same for simplicity, never in production.
- **Scaling path:** any database can move to its own cluster later without code changes (connection string only).
- **No cross-service joins, no cross-service foreign keys.** References to other services' entities are stored as UUIDs without FK constraints, plus snapshots where history matters.

### 1.2 Conventions

| Concern | Convention |
|---|---|
| Primary keys | `uuid` (UUIDv7, generated in application — time-ordered for index locality) |
| Human-facing numbers | Separate columns: `order_number`, `invoice_number` (never expose sequential PKs) |
| Money | `amount_minor bigint` + `currency char(3)`; never `float`/`double`/`numeric` in Java `double` |
| Time | `timestamptz`, stored in UTC |
| Enums | `text` + `CHECK` constraint (easier migrations than PG enum types) |
| Optimistic locking | `version bigint` on mutable aggregates (JPA `@Version`) |
| Audit columns | `created_at`, `updated_at`, `created_by`, `updated_by` (actor ID + actor type) |
| Soft delete | Avoided for business records; use status (`ARCHIVED`, `DISABLED`). Hard delete only for true junk (e.g. abandoned uploads) and for privacy erasure |
| Storefront | `storefront text CHECK (storefront IN ('SHOP','DIGITAL'))` on every admin-owned aggregate; indexed as leading column of admin query indexes |
| JSON | `jsonb` only for snapshots and genuinely schemaless data (addresses snapshot, raw webhook payloads, variant attributes) |
| Case-insensitive email | `citext` |

### 1.3 Shared infrastructure tables (present in every service DB that needs them)

```
outbox_events(
  id uuid PK, aggregate_type text, aggregate_id uuid, event_type text,
  schema_version int, payload jsonb, storefront text, created_at timestamptz,
  published_at timestamptz NULL, attempts int
)  -- index on (published_at) WHERE published_at IS NULL

processed_events(
  event_id uuid PK, event_type text, processed_at timestamptz
)

idempotency_keys(
  key text, scope text,             -- scope = actor id + endpoint
  request_hash text, response_status int, response_body jsonb,
  created_at timestamptz, expires_at timestamptz,
  PRIMARY KEY (scope, key)
)

admin_audit_log(
  id uuid PK, storefront text, actor_admin_id uuid, action text,
  entity_type text, entity_id uuid, before jsonb, after jsonb,
  ip_hash text, request_id text, created_at timestamptz
)  -- append-only; runtime role has INSERT/SELECT only
```

## 2. Schemas by service

### 2.1 `identity_db`

```
customers(
  id uuid PK, email citext UNIQUE, password_hash text,       -- Argon2id
  display_name text, phone text NULL,
  status text CHECK (status IN ('PENDING_VERIFICATION','ACTIVE','LOCKED','DISABLED','ERASED')),
  email_verified_at timestamptz NULL, last_login_at timestamptz NULL,
  created_at, updated_at, version
)

admin_users(
  id uuid PK,
  storefront text NOT NULL,                                  -- SHOP | DIGITAL, immutable
  email citext NOT NULL, password_hash text,
  mfa_secret_enc bytea NULL, mfa_enrolled_at timestamptz NULL,
  must_change_password boolean,
  status text CHECK (status IN ('INVITED','ACTIVE','LOCKED','DISABLED')),
  created_at, updated_at, version,
  UNIQUE (storefront, email)
)

admin_roles(
  id uuid PK, storefront text NOT NULL, code text NOT NULL,  -- MASTER_ADMIN, CATALOG_MANAGER, ...
  name text, is_system boolean,
  UNIQUE (storefront, code)
)

admin_permissions(code text PK, description text)            -- e.g. catalog:product:write

admin_role_permissions(role_id uuid FK, permission_code text FK, PRIMARY KEY (role_id, permission_code))

admin_user_roles(
  admin_user_id uuid FK, role_id uuid FK,
  PRIMARY KEY (admin_user_id, role_id)
)  -- trigger/app check: admin_users.storefront = admin_roles.storefront

admin_invitations(
  id uuid PK, storefront text, email citext, role_ids uuid[],
  token_hash text UNIQUE, invited_by uuid, expires_at, accepted_at NULL
)

refresh_tokens(
  id uuid PK, subject_type text CHECK (subject_type IN ('CUSTOMER','ADMIN')),
  subject_id uuid, session_id uuid, family_id uuid,
  token_hash text UNIQUE, issued_at, expires_at, revoked_at NULL,
  replaced_by uuid NULL, user_agent_hash text, ip_hash text
)

email_verification_tokens(id, customer_id, token_hash UNIQUE, expires_at, used_at)
password_reset_tokens(id, subject_type, subject_id, token_hash UNIQUE, expires_at, used_at)
service_clients(...)                                         -- Spring Authorization Server registered clients
signing_keys(kid text PK, public_jwk jsonb, private_key_ref text, status, created_at, retired_at)
```

Invariants:

- An admin user can only hold roles of its own storefront (enforced in application + DB trigger).
- There is no table, column, or role that grants authority across storefronts.
- At least one `ACTIVE` `MASTER_ADMIN` per storefront must remain (prevent self-lockout on disable/demote).

### 2.2 `catalog_db`

```
categories(
  id uuid PK, storefront text, parent_id uuid NULL FK, slug text, name text,
  sort_order int, status text,
  UNIQUE (storefront, slug)
)

products(
  id uuid PK, storefront text NOT NULL,
  product_type text NOT NULL CHECK (product_type IN ('PHYSICAL','DIGITAL')),
  selling_method text NOT NULL CHECK (selling_method IN ('DIRECT','AFFILIATE')),
  status text CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
  slug text NOT NULL, title text, summary text, description text,
  brand text NULL, category_id uuid NULL FK,
  search_vector tsvector,                                    -- generated, GIN index
  type_locked boolean DEFAULT false,                         -- true after first order/click; type & method become immutable
  published_at NULL, created_at, updated_at, version,
  UNIQUE (storefront, slug)
)

product_variants(
  id uuid PK, product_id uuid FK, sku text UNIQUE NULL,      -- SKU required for PHYSICAL+DIRECT
  name text, attributes jsonb,                               -- e.g. {"size":"M","color":"Black"} or {"license":"Commercial"}
  is_default boolean, status text, sort_order int,
  created_at, updated_at, version
)

variant_prices(                                              -- DIRECT only
  id uuid PK, variant_id uuid FK, currency char(3),
  amount_minor bigint CHECK (amount_minor >= 0),
  compare_at_minor bigint NULL,
  valid_from timestamptz, valid_to timestamptz NULL
)  -- exclusion constraint: no overlapping validity per (variant_id, currency)

physical_specs(                                              -- PHYSICAL only
  variant_id uuid PK FK, weight_grams int, length_mm int, width_mm int, height_mm int,
  shipping_class text, hs_code text NULL
)

digital_specs(                                               -- DIGITAL only (display data; files live in delivery)
  product_id uuid PK FK, format text, delivery_kind text CHECK (delivery_kind IN ('DOWNLOAD','LICENSE_KEY','DOWNLOAD_AND_LICENSE')),
  file_size_display text NULL, requirements text NULL
)

affiliate_offers(                                            -- AFFILIATE only
  product_id uuid PK FK, merchant_id uuid NOT NULL,          -- ref to affiliate-service (no FK)
  destination_url text NOT NULL,
  display_price_minor bigint NULL, display_currency char(3) NULL,
  price_checked_at timestamptz NULL,
  link_code text UNIQUE NOT NULL,                            -- used in /go/{code}
  disclosure_text text
)

product_media(
  id uuid PK, product_id uuid FK, object_key text, alt_text text,
  width int, height int, sort_order int
)

product_tags(product_id uuid, tag text, PRIMARY KEY (product_id, tag))
```

Invariants (application-enforced at publish, partially DB-enforced):

- `DIRECT` ⇒ every active variant has a current price; no `affiliate_offers` row.
- `AFFILIATE` ⇒ exactly one `affiliate_offers` row; no `variant_prices`; exactly one default variant.
- `PHYSICAL` + `DIRECT` ⇒ every active variant has SKU and `physical_specs`.
- `DIGITAL` ⇒ `digital_specs` row; no `physical_specs`.
- `type_locked = true` ⇒ `product_type`, `selling_method`, `storefront` cannot change.

### 2.3 `commerce_db`

```
carts(
  id uuid PK, customer_id uuid, storefront text, status text CHECK (status IN ('ACTIVE','CONVERTED','ABANDONED')),
  created_at, updated_at, version
)  -- partial unique index (customer_id, storefront) WHERE status = 'ACTIVE'

cart_items(
  id uuid PK, cart_id uuid FK, product_id uuid, variant_id uuid,
  quantity int CHECK (quantity > 0), added_at,
  UNIQUE (cart_id, variant_id)
)  -- NO price columns: prices are always fetched from catalog

customer_addresses(
  id uuid PK, customer_id uuid, label text, full_name text, line1, line2, city, region,
  postal_code, country char(2), phone, is_default_shipping bool, is_default_billing bool
)

orders(
  id uuid PK, order_number text UNIQUE,                      -- e.g. SHOP-2026-000123
  storefront text, customer_id uuid,
  status text CHECK (status IN (
    'PENDING_PAYMENT','PAID','PARTIALLY_FULFILLED','FULFILLED','COMPLETED',
    'PAYMENT_FAILED','EXPIRED','CANCELLED','PARTIALLY_REFUNDED','REFUNDED','PAID_UNFULFILLABLE')),
  currency char(3),
  subtotal_minor bigint, discount_minor bigint, shipping_minor bigint, tax_minor bigint, total_minor bigint,
  shipping_address jsonb NULL, billing_address jsonb,
  shipping_method_code text NULL,
  customer_email_snapshot citext,
  placed_at, paid_at NULL, cancelled_at NULL, expires_at,
  created_at, updated_at, version
)  -- CHECK total_minor = subtotal - discount + shipping + tax

order_lines(
  id uuid PK, order_id uuid FK, line_no int,
  product_id uuid, variant_id uuid,
  product_type text CHECK (product_type IN ('PHYSICAL','DIGITAL')),
  selling_method text CHECK (selling_method = 'DIRECT'),     -- affiliate can never be an order line
  sku_snapshot text NULL, title_snapshot text, variant_name_snapshot text,
  unit_price_minor bigint, quantity int, discount_minor bigint, tax_minor bigint, line_total_minor bigint,
  fulfillment_status text CHECK (fulfillment_status IN ('UNFULFILLED','RESERVED','SHIPPED','DELIVERED','ENTITLED','CANCELLED','REFUNDED'))
)

order_status_history(id, order_id, from_status, to_status, reason, actor_type, actor_id, created_at)

inventory_items(
  variant_id uuid PK, storefront text, sku text,
  on_hand int CHECK (on_hand >= 0),
  reserved int CHECK (reserved >= 0),
  low_stock_threshold int, version,
  CHECK (reserved <= on_hand)
)

inventory_reservations(
  id uuid PK, order_id uuid, variant_id uuid, quantity int,
  status text CHECK (status IN ('ACTIVE','COMMITTED','RELEASED','EXPIRED')),
  expires_at, created_at
)

inventory_movements(                                         -- append-only ledger
  id uuid PK, variant_id uuid, delta int,
  reason text CHECK (reason IN ('ADJUSTMENT','RECEIPT','SALE','RESTOCK','DAMAGE','CORRECTION')),
  order_id uuid NULL, actor_admin_id uuid NULL, note text, created_at
)

shipping_methods(id, storefront, code, name, rate_rules jsonb, active, UNIQUE (storefront, code))

shipments(
  id uuid PK, order_id uuid FK, carrier text, service_level text, tracking_number text NULL,
  status text CHECK (status IN ('PENDING','LABEL_CREATED','DISPATCHED','IN_TRANSIT','DELIVERED','RETURNED','CANCELLED')),
  dispatched_at NULL, delivered_at NULL, created_at, updated_at, version
)
shipment_lines(shipment_id uuid FK, order_line_id uuid FK, quantity int, PRIMARY KEY (shipment_id, order_line_id))

tax_rules(id, storefront, country char(2), region text NULL, product_type text NULL, rate_bps int, valid_from, valid_to)

invoice_sequences(storefront text, fiscal_year int, next_value bigint, PRIMARY KEY (storefront, fiscal_year))
  -- incremented with SELECT ... FOR UPDATE inside the order-paid transaction → gapless

invoices(
  id uuid PK, storefront text, invoice_number text, order_id uuid,
  kind text CHECK (kind IN ('INVOICE','CREDIT_NOTE')), related_invoice_id uuid NULL,
  currency char(3), subtotal_minor, tax_minor, total_minor,
  seller_snapshot jsonb, buyer_snapshot jsonb, lines_snapshot jsonb,
  pdf_object_key text NULL, issued_at,
  UNIQUE (storefront, invoice_number)
)  -- immutable: runtime role has no UPDATE except pdf_object_key fill-in
```

Concurrency: stock reservation uses a conditional update — `UPDATE inventory_items SET reserved = reserved + :q, version = version + 1 WHERE variant_id = :v AND on_hand - reserved >= :q` — and fails the checkout if 0 rows are affected. No application-level read-then-write.

### 2.4 `payment_db`

```
payments(
  id uuid PK, order_id uuid UNIQUE, storefront text,
  provider text, provider_payment_id text NULL, provider_session_id text NULL,
  amount_minor bigint, currency char(3),
  status text CHECK (status IN ('CREATED','REQUIRES_ACTION','PROCESSING','SUCCEEDED','FAILED','CANCELLED','PARTIALLY_REFUNDED','REFUNDED')),
  failure_code text NULL, created_at, updated_at, version,
  UNIQUE (provider, provider_payment_id)
)

refunds(
  id uuid PK, payment_id uuid FK, amount_minor bigint, reason text,
  status text CHECK (status IN ('REQUESTED','PROCESSING','SUCCEEDED','FAILED')),
  provider_refund_id text NULL, requested_by_admin_id uuid, requested_at, completed_at NULL
)  -- application check: sum(succeeded+processing refunds) <= payment amount

webhook_events(
  id uuid PK, provider text, provider_event_id text, event_type text,
  payload jsonb, signature_valid boolean, received_at, processed_at NULL, error text NULL,
  UNIQUE (provider, provider_event_id)
)
```

No card numbers, CVV, or bank credentials are ever stored.

### 2.5 `delivery_db`

```
digital_assets(
  id uuid PK, storefront text, product_id uuid, variant_id uuid NULL,
  object_key text UNIQUE, original_filename text, mime_type text,
  size_bytes bigint, sha256 char(64), version_label text,
  status text CHECK (status IN ('UPLOADING','SCANNING','READY','REJECTED','RETIRED')),
  uploaded_by_admin_id uuid, created_at, ready_at NULL
)

delivery_policies(
  product_id uuid PK, storefront text,
  download_limit int NULL,          -- NULL = unlimited
  access_days int NULL,             -- NULL = lifetime
  version_policy text CHECK (version_policy IN ('PURCHASED_VERSION','LATEST'))
)

license_pools(id uuid PK, storefront text, product_id uuid, variant_id uuid NULL, low_threshold int)
license_keys(
  id uuid PK, pool_id uuid FK, key_ciphertext bytea, key_fingerprint char(64) UNIQUE,
  status text CHECK (status IN ('AVAILABLE','ASSIGNED','REVOKED')),
  entitlement_id uuid NULL, assigned_at NULL
)  -- assignment: SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1

entitlements(
  id uuid PK, storefront text, customer_id uuid, product_id uuid, variant_id uuid,
  order_id uuid, order_line_id uuid UNIQUE,                 -- idempotent grant
  source text CHECK (source IN ('PURCHASE','ADMIN_GRANT')),
  status text CHECK (status IN ('ACTIVE','REVOKED','EXPIRED')),
  granted_at, expires_at NULL, revoked_at NULL, revoke_reason text NULL,
  downloads_used int DEFAULT 0, version
)  -- index (customer_id, status)

download_events(
  id uuid PK, entitlement_id uuid, asset_id uuid, issued_at, url_expires_at,
  ip_hash text, user_agent_hash text
)
```

### 2.6 `affiliate_db`

```
networks(
  id uuid PK, storefront text, name text, subid_param text,
  postback_secret_enc bytea, allowed_postback_ips cidr[] NULL, status text
)

merchants(
  id uuid PK, storefront text, network_id uuid NULL FK, name text,
  allowed_domains text[] NOT NULL,                           -- exact host or *.example.com
  url_template text NULL,                                    -- e.g. {destination}?{subid_param}={clickId}
  status text
)

affiliate_links(                                             -- read model from catalog events
  code text PK, storefront text, product_id uuid, merchant_id uuid FK,
  destination_url text, active boolean, updated_from_event_id uuid, updated_at
)

clicks(
  id uuid, click_id text,                                    -- opaque, URL-safe, unguessable
  code text, storefront text, product_id uuid, merchant_id uuid,
  customer_id uuid NULL, session_hash text, ip_hash text, ua_hash text,
  referrer_host text NULL, is_suspected_bot boolean, created_at timestamptz,
  PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);                           -- monthly partitions, retention policy

conversions(
  id uuid PK, storefront text, network_id uuid, click_id text NULL,
  external_txn_id text, sale_amount_minor bigint, commission_minor bigint, currency char(3),
  status text CHECK (status IN ('PENDING','APPROVED','REVERSED','PAID')),
  source text CHECK (source IN ('POSTBACK','IMPORT')), raw jsonb, reported_at, updated_at,
  UNIQUE (network_id, external_txn_id)
)

report_imports(id, storefront, network_id, object_key, status, rows_total, rows_matched, imported_by, created_at)
```

### 2.7 `notification_db`

```
templates(
  id uuid PK, storefront text, code text, channel text CHECK (channel IN ('EMAIL','SMS')),
  locale text, subject text, body text, version int, active boolean,
  UNIQUE (storefront, code, channel, locale, version)
)

notifications(
  id uuid PK, storefront text, source_event_id uuid, template_code text, channel text,
  recipient text,                                            -- email/phone; purged per retention policy
  status text CHECK (status IN ('QUEUED','SENT','FAILED','SUPPRESSED')),
  attempts int, last_error text NULL, provider_message_id text NULL,
  created_at, sent_at NULL,
  UNIQUE (source_event_id, template_code, channel)          -- no duplicate emails per event
)
```

## 3. Data ownership & replication map

| Data | Owner | Copies elsewhere (and why) |
|---|---|---|
| Product price | catalog | Snapshot in `order_lines` (history) |
| Product title/SKU | catalog | Snapshot in `order_lines`, `invoices.lines_snapshot` |
| Affiliate destination | catalog (offer) | Read model `affiliate_links` (fast redirects without sync call) |
| Stock | commerce | None |
| Payment status | payment | Order status derived from events in commerce |
| Entitlement | delivery | None (library is served by delivery) |
| Customer email | identity | Snapshot in orders (receipts), notifications (send log) |

## 4. Operations

- **Backups:** continuous WAL archiving + daily base backups (managed PostgreSQL PITR in production). Restore drill quarterly.
- **Retention:** clicks — raw rows 13 months then aggregated; notifications recipient field — 90 days; webhook payloads — 180 days; invoices — per tax law (assume ≥ 8 years; confirm jurisdiction).
- **Privacy erasure:** customer erasure sets `customers.status = 'ERASED'`, nulls PII, and emits `identity.customer.erased`; other services pseudonymize. Invoices retain legally required buyer data.
- **Migrations:** expand → migrate → contract; no destructive change in the same release that stops using a column.
- **Connection pooling:** HikariCP per service with conservative pool sizes; PgBouncer (transaction mode) if total connections approach the cluster limit.
