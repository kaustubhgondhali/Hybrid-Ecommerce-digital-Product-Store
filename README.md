# Affiliate Product Platform (Elaashop + Elaatech)

One domain, two independent **affiliate** storefronts:

| Storefront | Store | Admin panel |
|---|---|---|
| Physical / lifestyle deals | `/shop` (Elaashop) | `/shop/admin` |
| Digital deals | `/digital` (Elaatech) | `/digital/admin` |

By default the site is **affiliate-only** (`AFFILIATE_ONLY=true`): products are partner offers. Shoppers click
**Buy from partner** and complete purchase on the merchant site (`/go/{code}` tracks the click). There is no
customer cart or checkout on this domain. There is **no global admin**: a shop admin cannot see or change anything
in the digital admin, and vice versa.

The server is authoritative for affiliate link validation, click tracking and admin permissions. The browser only
displays what the server returns. Set `AFFILIATE_ONLY=false` only if you also want to sell products yourself
(cart, payments, inventory) on this site.

**Stack:** Spring Boot 3.5 (Java 21, one application) + MySQL 8 + plain HTML/CSS/JavaScript (no build step).

## Status

| Area | State |
|---|---|
| **Affiliate-only mode** (default): partner offers, `/go/{code}` redirects, no storefront cart/checkout | Done |
| Catalog, categories, images, variants, publish rules | Done |
| Cart, checkout, stock reservation (optional; off while `AFFILIATE_ONLY=true`) | Available when affiliate-only is disabled |
| Payments | Only needed if you turn off affiliate-only. Demo provider for that path; Razorpay planned. |
| Orders: cancel, refund (full/partial, optional restock), shipping + tracking, delivered | Done |
| GST-style invoices with gapless numbering per storefront and year, print / save as PDF | Done |
| Digital delivery: files (signed short-lived download links), license keys, customer library | Done |
| Affiliate: merchants + domain allowlist, `/go/{code}` click tracking, sub-IDs, CSV conversion import, report | Done |
| Customer accounts: register, verify email, password reset, order history | Done |
| Admin: roles and permissions, staff, MFA (authenticator app), audit log, forced password change | Done |
| Email (SMTP) configured per storefront in the admin panel, test email, email log | Done |
| Email outbox: every email is queued, retried automatically (1 min → 3 h) and can be retried from the email log | Done |
| GST: HSN/SAC and rate per product, CGST+SGST vs IGST by place of supply, buyer GSTIN, tax-inclusive or exclusive prices | Done |
| Coupons (percent / fixed, cap, minimum order, dates, usage limits), cash on delivery (shop), PIN code delivery check | Done |
| Product reviews with moderation, wishlist (per customer, per storefront) | Done |
| Content pages (legal templates: privacy, terms, refunds, shipping, affiliate disclosure, about, contact) | Done |
| SEO: server-rendered titles/meta/Open Graph/JSON-LD, canonical URLs, sitemap.xml, robots.txt, 301 redirects, 404 monitor, image thumbnails | Done |
| **SEO analyzer** in each admin: site audit, per-product score with checks, live editor with Google preview | Done |
| Rate limiting on sign-in, registration, password reset, checkout, coupon checks, reviews, PIN code checks and affiliate redirects | Done |
| Sessions stored in MySQL (users stay signed in across restarts); MySQL full-text product search | Done |
| System health page: database, schema, email queue, storage, grouped server errors | Done |
| **Security analyzer** in each admin: scored defensive audit (HTTPS, cookies, HSTS, MFA, rate limits, CSRF/CSP, demo mode, …) | Done |

## Prerequisites

- **Java 21** (JDK)
- **Maven 3.9+** on `PATH`
- **MySQL 8** running locally (or use Docker Compose below)

## 1. Prepare MySQL

Create a database user for the app (run in the MySQL shell as root; choose your own password):

```sql
CREATE DATABASE hybrid_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'hybrid'@'localhost' IDENTIFIED BY 'choose-a-strong-password';
GRANT ALL PRIVILEGES ON hybrid_platform.* TO 'hybrid'@'localhost';
```

Tables are created automatically by Flyway on first start (`backend/src/main/resources/db/migration`).

## 2. Configure `.env`

```powershell
Copy-Item .env.example .env      # Windows
# cp .env.example .env           # macOS / Linux
```

Fill in at least:

- `DB_USERNAME`, `DB_PASSWORD`: the MySQL user from step 1
- `APP_ENCRYPTION_KEY`: 32 random bytes, base64 (the command is in `.env.example`). It encrypts SMTP passwords,
  MFA secrets and license keys. Back it up; if it changes, those values can no longer be read.
- `APP_HASH_SALT`: any long random string
- `BOOTSTRAP_SHOP_ADMIN_EMAIL` / `_PASSWORD` and `BOOTSTRAP_DIGITAL_ADMIN_EMAIL` / `_PASSWORD`: the first admin of
  each storefront. They are created once, on first start, and must change the password at first sign-in.

`.env` is git-ignored. Never commit real credentials.

## 3. Run

```powershell
.\scripts\run-dev.ps1            # Windows
# ./scripts/run-dev.sh           # macOS / Linux
```

or manually: `cd backend` then `mvn spring-boot:run` (must be started from `backend/`).

Open:

- Store: <http://localhost:8080/shop> and <http://localhost:8080/digital>
- Admin: <http://localhost:8080/shop/admin> and <http://localhost:8080/digital/admin>
- Health: <http://localhost:8080/actuator/health>

In the default `dev` profile the frontend is served directly from `frontend/`, so HTML/CSS/JS edits show after a
browser refresh without restarting. Emails that cannot be sent (SMTP not configured) are printed to the console.

On first start (or whenever a demo slug is missing), the app seeds a few **[Demo]** products on both
storefronts so you can see how the catalog, product page, partner offers and digital licences look. They are
marked with a Demo badge, `noindex`, and titles starting with `[Demo]`. Delete them in each admin panel before
launch, or set `DEMO_CATALOG=false` in `.env` to stop reseeding missing ones.

## 4. First-time setup in each admin panel

1. Sign in with the bootstrap admin and choose a new password.
2. **Settings → Store & invoice details**: store name, support email, seller legal name, GSTIN, registered
   address, tax rate, invoice prefix. These are printed on invoices.
3. **Settings → Shipping methods** (shop): adjust the default "Standard delivery" rate or add more.
4. **Settings → Email (SMTP)**: host, port, security (STARTTLS / SSL), username, password, from address. Use
   **Send test email** to check it. Until SMTP is enabled, emails are logged as "Skipped" in **Email log**.
5. **Affiliate links**: add them on each affiliate product's page. Any `https` affiliate URL works (plain `http` only if enabled for that merchant) (Amazon,
   Flipkart, Cuelinks, EarnKaro, network deep links, short links...). The first link to a new domain asks you to
   confirm it; the domain is then allowed for that storefront (manage them under **Affiliate → Merchants**).
6. **My account**: turn on MFA (authenticator app) for admin accounts.
7. **Staff**: add more staff with limited roles (Catalog manager, Order manager, Support).
8. **Settings → Store & invoice details**: seller state, default GST rate and HSN/SAC code, whether prices include
   GST. Set the HSN/SAC and GST rate on each product if it differs from the default.
9. **Settings → Delivery, cash on delivery & reviews**: deliverable PIN codes (all / allow list / block list, prefixes allowed),
   delivery estimate, cash on delivery (shop only: limit, fee, PIN codes) and review auto-approval.
10. **Pages**: the legal pages are created as templates. Replace every `[bracketed]` value and the template note
    before launch; the SEO audit flags pages that still contain them.
11. **SEO → Settings**: home page title and description, Google / Bing verification codes, extra robots.txt rules. Then submit `https://your-domain/sitemap.xml` in Google Search Console.
12. **SEO → Products**: work through the products with the lowest score. Each check says what to fix.

## SEO

- Store, category, product and content pages are rendered on the server with their title, meta description,
  canonical URL, Open Graph / Twitter tags and JSON-LD (Organization, WebSite search, BreadcrumbList, Product with
  price, availability and ratings), so search engines see full content without running JavaScript.
- `/sitemap.xml` (with product images) and `/robots.txt` are generated from published content. Cart, checkout,
  account and admin pages are `noindex`.
- Changing a product, category or page URL creates a 301 redirect from the old URL automatically. Manual redirects
  and the list of URLs that returned 404 are under **SEO → Redirects / 404 monitor**.
- The **SEO analyzer** scores each product 0-100 (title and description length, focus keyword placement and
  density, content length, images and alt text, URL, duplicates, structured data, reviews, indexability), shows a
  Google result preview while you edit, and audits the whole site.

## Payments

`PAYMENT_PROVIDER=demo` (the default) shows a clearly labelled demo payment screen with "Simulate successful
payment" / "Simulate failed payment" buttons. It runs through the same server-side flow a real provider will use:
payment events, amount checks, stock commit, invoice and digital delivery. **The demo provider refuses to start
when the `prod` profile is active**, so it cannot accidentally go live. Razorpay will replace it.

## Tests

```powershell
cd backend
mvn -B verify
```

Runs unit tests (affiliate URL policy, rate limiter, GST arithmetic, SEO analyzer, content markup escaping, coupon
discounts, PIN code rules, state names, error path grouping) and end-to-end integration tests against an in-memory
H2 database in MySQL mode: admin setup, publish rules, stock, affiliate redirect, cart, checkout, demo payment
failure → retry → success, invoice, shipping, a digital license-key product delivered to the customer library,
coupon + cash on delivery through to delivery, wishlist, and sessions stored in the database. No MySQL is needed
for tests. The jar is written to `backend/target/hybrid-platform.jar`.

**CI:** `.github/workflows/ci.yml` runs the tests and a JavaScript syntax check on every push and pull request, and
starts the app against a real MySQL 8 to check the migrations (including the full-text index), robots.txt and the
sitemap. It uses throwaway CI-only database credentials.

## Backups

```powershell
.\scripts\backup.ps1 -Destination D:\backups -KeepDays 14      # Windows (Task Scheduler, daily)
# scripts/backup.sh /var/backups/hybrid 14                     # Linux (cron, daily)
```

Dumps the database with `mysqldump` (consistent snapshot, no table locks) and archives the upload folder
(`STORAGE_ROOT`), then deletes backups older than the given number of days. Database credentials are read from
`.env` and passed through a temporary options file, never on the command line. Keep a copy off the server and
back up `APP_ENCRYPTION_KEY` separately: without it, saved SMTP passwords, MFA secrets and license keys cannot be
decrypted. Restore with `mysql hybrid_platform < db-....sql` and by unpacking the storage archive into `STORAGE_ROOT`.

## Docker

```bash
docker compose up --build
```

Starts MySQL 8 and the app. Uses `.env` for credentials (also set `MYSQL_ROOT_PASSWORD`). Data persists in the
`mysql-data` and `app-data` volumes. The container runs with the `docker` profile; switch to `prod` (secure cookies,
strict startup checks) once Razorpay is configured.

## Security analyzer

Each admin panel has **Security** (permission: Audit). It scores this storefront’s defensive posture
(HTTPS, secure cookies, HSTS, encryption key, MFA coverage, rate limits, CSRF/CSP, demo payments/catalog,
affiliate HTTP merchants, unresolved errors) and lists what to fix first. It does **not** attack your site
or scan other networks.

Hardening already built in: CSRF, CSP, clickjacking deny, Permissions-Policy, rate limits, login lockout,
session rotation on sign-in, JDBC sessions, encrypted secrets. With `COOKIE_SECURE=true` the app also sends
**HSTS** (1 year, includeSubDomains) and CSP `upgrade-insecure-requests`.

## Production checklist

- Serve over HTTPS behind a reverse proxy; set `COOKIE_SECURE=true` and `APP_BASE_URL=https://your-domain`
- Run with `SPRING_PROFILES_ACTIVE=prod` (requires `APP_ENCRYPTION_KEY` and `APP_HASH_SALT`)
- Enable MFA on every staff account (especially Master Admin); open **Security** until the score is Strong
- Set `REQUIRE_EMAIL_VERIFICATION=true` once SMTP works; set `DEMO_CATALOG=false` and delete demo products
- Configure a real payment provider (Razorpay, pending)
- Schedule `scripts/backup.ps1` / `backup.sh` daily (MySQL + `STORAGE_ROOT`) and copy backups off the server
- Configure SMTP in each admin panel and fill in the legal pages
- Check **System health** and **Security** in each admin after deploying

## Layout

```
backend/     Spring Boot application (Maven). Packages by feature: catalog, commerce (cart, checkout, orders,
             invoices, settings), payment, delivery, affiliate, notification, identity, security, web
frontend/    Plain HTML/CSS/JS: store/, account/, admin/, assets/ (bundled into the jar at build time)
scripts/     run-dev.ps1 / run-dev.sh, backup.ps1 / backup.sh
.github/     CI workflow
docs/        Architecture notes and decision records
```

## Known limitations

- Razorpay is not integrated yet (demo payments only).
- Uploaded files are type- and size-checked but not malware-scanned.
- Affiliate sales are imported from network CSV reports; automatic postbacks/APIs per network are not built yet.
- MFA setup shows a setup key / `otpauth://` URI to enter in the authenticator app (no QR code image).
- Image thumbnails are generated for JPEG and PNG; WebP uploads are served at their original size.
- Coupon start / end dates can be changed but not cleared once set (deactivate the coupon instead).
- Ranked full-text search needs MySQL; on other databases (tests use H2) search falls back to simple matching.
- The server error log is shared by the application; each admin only sees errors from its own storefront's URLs.
