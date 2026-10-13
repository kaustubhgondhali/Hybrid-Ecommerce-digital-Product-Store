# Affiliate Product Platform (Elaashop + Elaatech)

One domain, two independent **affiliate** storefronts:

| Storefront | Store | Admin panel |
|---|---|---|
| Physical / lifestyle deals | `/shop` (Elaashop) | `/shop/admin` |
| Digital deals | `/digital` (Elaatech) | `/digital/admin` |

By default the site is **affiliate-only** (`AFFILIATE_ONLY=true`): products are partner offers. Shoppers click
**Buy from partner** and complete purchase on the merchant site (`/go/{code}` tracks the click). Customers can
**save deals in their cart** to buy later on the partner site, but there is no checkout on this domain. There is
**no global admin**: a shop admin cannot see or change anything in the digital admin, and vice versa.

The server is authoritative for affiliate link validation, click tracking and admin permissions. The browser only
displays what the server returns. Set `AFFILIATE_ONLY=false` only if you also want to sell products yourself
(cart, payments, inventory) on this site.

**Stack:** Spring Boot 3.5 (Java 21, one application) + MySQL 8 + plain HTML/CSS/JavaScript (no build step).

## Status

| Area | State |
|---|---|
| **Affiliate-only mode** (default): partner offers, `/go/{code}` redirects, cart as "saved deals", no checkout | Done |
| Catalog, categories, images, variants, publish rules | Done |
| Cart, checkout, stock reservation (optional; off while `AFFILIATE_ONLY=true`) | Available when affiliate-only is disabled |
| Payments | Only needed if you turn off affiliate-only. Demo provider for that path; Razorpay planned. |
| Orders: cancel, refund (full/partial, optional restock), shipping + tracking, delivered | Done |
| GST-style invoices with gapless numbering per storefront and year, print / save as PDF | Done |
| Digital delivery: files (signed short-lived download links), license keys, customer library | Done |
| Affiliate: merchants + domain allowlist, `/go/{code}` click tracking, sub-IDs, CSV import + signed postback URL, conversion rate per product/merchant | Done |
| Affiliate prices: hidden on the storefront once older than `AFFILIATE_PRICE_MAX_AGE_DAYS` until re-checked; price-drop email (≥5%, max one per product per customer every 7 days) to customers with the deal in their cart or wishlist; never for stores whose prices are hidden (Amazon) | Done |
| **Deal pages**: discount badges, "lowest in 30 days" and price-drop flags, 30-day price range, verdict, pros and cons, our rating, similar deals; home page with featured deals, top deals, price drops and trending | Done |
| **Compare stores**: several partner stores per product, cheapest first; Amazon prices hidden by the merchant's "Hide prices" rule ("Check price") | Done |
| **Free automations**: nightly broken-link check (pauses dead affiliate links), welcome email, weekly deals digest, review-request email, daily store summary for master admins, Telegram channel posts for new deals and price drops | Done |
| **Web Stories** (Google's AMP format): built in each admin (photos, short videos, or one click from 3-10 products), shown at the bottom of store pages, on `/{store}/stories` and in the sitemap | Done |
| **Product videos**: up to 3 MP4/WebM clips per product (100 MB each) with a poster frame captured in the browser; shown in the gallery after the main image, with VideoObject markup for Google | Done |
| **Storefront layout** in an Apple-like style: translucent top bar with search panel and phone menu, large centred hero with the store logo, category row, sideways deal shelves, sticky buy bar on product pages, fade-in on scroll (brand colours and logos unchanged) | Done |
| Digital storefront: 30 categories (courses, SaaS, AI tools, templates, ...) in priority order; empty categories stay out of menus and the sitemap | Done |
| **Research** admin tool: niche + notes → categories, products to search for, and which affiliate programs fit | Done |
| **Shopping features**: search suggestions while typing, sort and filters (store, discount, price), specifications table, compare up to 4 products, recently viewed, honest "clicked this week" counts, share buttons | Done |
| **Coupons & offers** (`/{store}/offers`) and a page per partner store (`/{store}/store/{domain}`); "Copy code & go" through a tracked link; Deal of the Day with countdown; price-drop alerts and newsletter (both confirmed by email); WhatsApp chat button | Done |
| **Sales & demand** admin tool: today / this week / this month / last 6 months / this year, compared with the period before | Done |
| **Buying guides** (`/{store}/guides`): articles with product cards and "top picks" rows, contents box, Article data for Google | Done |
| **Installable app** (PWA): "Install the app" / Add to Home screen, offline page | Done |
| **Google Analytics 4 and Meta Pixel**, loaded only after the shopper accepts cookies | Done |
| **CSV bulk import** of affiliate products (check first, then import as drafts) | Done |
| Customer accounts: register, verify email, password reset, order history | Done |
| Admin: roles and permissions, staff, MFA (authenticator app), audit log, forced password change | Done |
| Email (SMTP) configured per storefront in the admin panel, test email, email log | Done |
| Email outbox: every email is queued, retried automatically (1 min → 3 h) and can be retried from the email log | Done |
| GST: HSN/SAC and rate per product, CGST+SGST vs IGST by place of supply, buyer GSTIN, tax-inclusive or exclusive prices | Done |
| Coupons (percent / fixed, cap, minimum order, dates, usage limits), cash on delivery (shop), PIN code delivery check | Done |
| Product reviews: customer reviews wait for approval (count shown in the admin menu); staff can add reviews customers sent by WhatsApp, email, phone or in person, labelled with their source and never marked "verified purchase"; wishlist (per customer, per storefront) | Done |
| Content pages (legal templates: privacy, terms, refunds, shipping, affiliate disclosure, about, contact) and comparison tables (one row per line, cells split by pipes; "Insert comparison table" button in the editor) | Done |
| SEO: server-rendered titles/meta/Open Graph/JSON-LD, canonical URLs, sitemap.xml, robots.txt, 301 redirects, 404 monitor | Done |
| Images: 480 px grid thumbnail + 1200 px product-page size generated on upload (older images backfilled at startup), lazy loading | Done |
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

or double-click `start-backend.bat` (Windows), or manually: `cd backend` then `mvn spring-boot:run` (must be started
from `backend/`; in the main folder Maven reports "there is no POM in this directory").

Open:

- Store: <http://localhost:8080/shop> and <http://localhost:8080/digital>
- Admin: <http://localhost:8080/shop/admin> and <http://localhost:8080/digital/admin>
- Health: <http://localhost:8080/actuator/health>

In the default `dev` profile the frontend is served directly from `frontend/`, so HTML/CSS/JS edits show after a
browser refresh without restarting. Emails that cannot be sent (SMTP not configured) are printed to the console.

On first start (or whenever a demo slug is missing), the app seeds a few **[Demo]** products on both
storefronts so you can see how the catalog, product page, partner offers and digital licences look. They are
marked with a Demo badge, `noindex`, and titles starting with `[Demo]`. Delete them in each admin panel before
launch, or set `DEMO_CATALOG=false` in `.env` to stop reseeding missing ones. The `prod` profile never seeds
them unless you set `DEMO_CATALOG=true` explicitly.

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
13. **CRM → Automations**: the broken-link check runs every night by default. Once email (SMTP) works, turn on the
    emails you want (welcome, saved-cart reminder, win-back, weekly digest, review request, daily summary).
    Optionally connect a Telegram channel (see **Automations** below).
14. **Reviews**: approve or reject customer reviews (the menu shows how many are waiting). Use **Add a review** for
    feedback customers sent you directly. Only add real customer feedback.
15. **Products → Images / Videos**: the first image is the main image (use **Make main** to change it). Add short
    MP4 clips (15-60 seconds) to the products that sell best.
16. **Web Stories**: create your first story from 3-10 products, then add stories with your own photos and videos.

## CRM

**CRM** in each admin panel turns sign-ups, saved carts, wishlists, partner clicks and sales into a to-do list for
selling. Each storefront sees only customers who signed up on it or used it.

- **Overview**: customers, email subscribers, hot leads, abandoned carts and repeat-buyer rate; a "contact these
  first" list ranked by a 0-100 buying-intent score; segment cards with one-click **View** and **Email**.
- **Segments** (automatic): hot leads, abandoned carts, clicked but no sale, wishlist never bought, new sign-ups,
  first-time buyers, repeat customers, VIP (3+ purchases), at risk (60-180 days since last purchase), lapsed, and
  email subscribers. Partner sales reported for a customer's affiliate clicks count as purchases.
- **Customer profile**: activity timeline (orders, cart, wishlist, partner clicks and sales, reviews, emails),
  suggested next step, tags, private staff notes, follow-up tasks, and email / call / WhatsApp links.
- **Campaigns**: plain-text emails to a segment with `{name}`, `{store}`, `{link}` and `{coupon}`, ready-made
  templates, a live preview, test send, and results counted for 7 days after sending (partner clicks and sales,
  commission, orders, revenue, coupon use).
- **Automations** (see below): customer emails that send themselves, plus store upkeep.

**Consent:** marketing email only goes to customers who opted in, per storefront: the unticked "email me offers"
box at sign-up, or **My account → Email preferences**. Staff cannot subscribe anyone. Every marketing email says why
it was sent and ends with a signed one-click unsubscribe link (`/account/unsubscribe.html`); the link is signed
with `APP_ENCRYPTION_KEY`, so keep that key stable. Order and account emails are not affected.

**Roles:** master admins can do everything; order managers and support staff can view the CRM and add notes, tags
and tasks; only master admins send campaigns and change automations; catalog managers do not see customer data.

The CRM builds segments in memory from one aggregate query, which suits stores with up to a few tens of thousands
of customers.

## Automations

**CRM → Automations** in each admin. Everything is free: it runs inside the app and uses your own SMTP mailbox and,
optionally, a free Telegram bot. Each automation is off until you turn it on, except the link check.

- **Customer emails** (checked hourly; only to customers who opted in; at most one marketing email per customer per
  run): welcome email with this week's top deals; saved-cart reminder after N hours (at most once per 3 days);
  win-back after N days without a purchase (at most once per 90 days); weekly deals digest on a chosen day; review
  request N days after a partner sale or delivered order.
- **Store upkeep**: every night at 3:30 (India time) each live affiliate link is checked. A link is paused only after
  two checks in a row return "not found" (404/410), and nothing is paused when many links fail at once (that is usually
  a network problem). **Check links now** runs it immediately. The **daily summary** emails master admins clicks,
  sales, new subscribers, broken links, stale prices, tasks due and reviews waiting.
- **Telegram channel**: create a bot with @BotFather, add it as an admin of your channel, paste the bot token and the
  channel (`@name` or its numeric id) and press **Send test message**. New deals and price drops are posted with a link
  to the product page on this site (never the raw affiliate link), and never for stores with hidden prices.

## Web Stories

Full-screen, tap-through stories in Google's Web Stories (AMP) format, made in each admin under **Web Stories**:

- **Create from products**: pick 3-10 published products; each becomes a page with its photo, name, first highlight
  and a "See the deal" swipe-up link to its product page. The first product's photo becomes the cover.
- **Start from scratch**: upload a cover (cropped to a 3:4 portrait in the browser) and pages with portrait photos or
  short MP4/WebM videos (up to 60 seconds and 30 MB; the browser captures the poster frame). Each page can have a
  headline, a line of text and a link to a product.
- Prices are added when a story is shown, so they are always current (and hidden for stores that hide prices).
- Publishing needs a cover and at least 3 pages. Published stories appear at the bottom of the home, category,
  product and content pages, on `/{store}/stories`, and in `sitemap.xml`. Drafts can be previewed by signed-in staff
  of that storefront (not indexed).
- Each story is a valid AMP page (checked with Google's AMP validator) with its own Content-Security-Policy that
  allows the AMP runtime from `cdn.ampproject.org`; all other pages keep the strict policy.
- Google shows stories only from a public HTTPS domain after crawling them; submit your sitemap in Search Console.

## Sales & demand

**Sales & demand** (right under Dashboard in each admin) answers "what sells and what do shoppers want?" for
**Today, This week, This month, Last 6 months or This year**, each compared with the same stretch of the period before
(for example this month so far vs the same days last month).

- **Most in demand**: products ranked by demand points: page view 1, save to wishlist or cart 3, price alert 3,
  click to the store 5, sale 20. Also shows click rate and a ▲/▼ trend.
- **Best sellers** (sales reported by your affiliate networks plus paid orders), **Rising fast**, **Top categories**,
  **Top partner stores**, an activity chart, and a funnel from views to clicks to sales.
- **What shoppers search for**, and **Searched, but you don't have it**: searches that found nothing are product ideas.
- **Market research (India)**: links to Google Trends (set to the chosen period), Amazon.in best sellers and
  movers & shakers, and Flipkart or Udemy sorted by popularity. These are links only; nothing is scraped.
- Product page views and searches are counted from the day this tool was installed: one view per product per visit,
  bots ignored, daily totals only (no personal data). Earnings are shown only to staff who can see affiliate data.

## Buying guides

Admin → **Buying guides** → **New guide**. "Start from an outline" fills in a proven structure (quick answer, top picks,
one section per product, comparison table, how to choose, FAQs). **Insert product** adds a product card (photo, price,
buy button); **Insert products row** adds 2-4 "top picks" side by side. Guides need a few sentences before they can be
published, can be previewed as drafts, and appear on `/{store}/guides`, on the home page, in the menu, on the product
pages they mention ("Featured in our guides") and in `sitemap.xml`. Changing the address of a published guide adds a
301 redirect automatically.

## Installable app, analytics and cookie consent

- Both storefronts can be installed like an app ("Install the app" in the footer and phone menu, or the browser's
  "Add to Home screen"). The app opens full screen with your logo, and shows a friendly page when there is no internet.
  The admin, accounts, checkout and the API are never cached. Installation needs HTTPS (or localhost).
- Settings → Store details: **Google Analytics 4 ID** (`G-…`) and **Meta Pixel ID**. They load only after a shopper
  presses "Accept all" in the cookie banner ("Only necessary" loads nothing). Shoppers can change their choice under
  "Cookie settings" in the footer. Tracked: page views, product views, searches, clicks to partner stores, newsletter
  sign-ups and price alerts. Only storefront pages allow the Google/Meta script hosts; the admin keeps the strict policy.

## Importing products from a spreadsheet

Admin → **Import products** → **Download template**, fill it in Excel or Google Sheets (save as "CSV UTF-8"), then
**Check file**. Columns: `title` and `link` (your affiliate link) are needed; `price` and `mrp` in rupees, `category`
(must exist), `brand`, `store`, `summary` and `highlights` (separated by `|`) are optional. Nothing is saved by the
check; every problem is explained per row. New partner stores must be approved with one tick. Each product is added as
a **draft**: add a photo and press Publish (photos are not copied from other sites; Amazon's rules don't allow it).
Up to 500 products per file; products already in the catalog (same title) are skipped.

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
coupon + cash on delivery through to delivery, wishlist, sessions stored in the database, admin role permissions
(Support / Catalog manager cannot write outside their role), `/go` redirects with click references, conversion
postbacks (token, idempotency, click matching), stale partner prices and price-drop alerts, and the CRM (consent at
sign-up, per-storefront customer data, segments and intent score, notes / tags / tasks and their permissions,
saved-cart reminders sent once, campaigns that reach subscribers only, results, signed unsubscribe links), deal
pages and store comparison, automations (link checker against a fake partner site, Telegram posts, welcome / digest /
review-request emails, daily summary), reviews (approval queue, staff-added reviews labelled with their source,
permissions), product videos (content checks, poster, limits, VideoObject markup) and Web Stories (publishing rules,
AMP page, previews, sitemap, renamed links, one-click stories from products, permissions). No MySQL is needed for
tests. The jar is written to `backend/target/hybrid-platform.jar`.

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
- Affiliate sales arrive by CSV import or the generic postback URL; network-specific reporting APIs are not built.
- Rate limits and uploaded files live on the single server. Before running more than one app server, move rate limits
  to a shared store (e.g. Redis) and files to object storage (e.g. S3).
- MFA setup shows a setup key / `otpauth://` URI to enter in the authenticator app (no QR code image).
- Image renditions are generated for JPEG and PNG; WebP uploads are served at their original size.
- Videos are stored as uploaded (no transcoding): use MP4 (H.264). The admin warns when the browser cannot play a file.
- Customers must sign in to write a review (keeps spam out of the approval queue).
- Web Stories load Google's AMP runtime from `cdn.ampproject.org`; story views are not counted in the admin yet.
- Coupon start / end dates can be changed but not cleared once set (deactivate the coupon instead).
- Ranked full-text search needs MySQL; on other databases (tests use H2) search falls back to simple matching.
- The server error log is shared by the application; each admin only sees errors from its own storefront's URLs.
