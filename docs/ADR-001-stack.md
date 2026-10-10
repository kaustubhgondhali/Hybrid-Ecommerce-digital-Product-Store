# ADR-001: Single Spring Boot application, plain HTML/JS frontend, MySQL

**Status:** Accepted, supersedes the microservices / Next.js / PostgreSQL design described in `ARCHITECTURE.md`,
`MICROSERVICES.md`, `DATABASE.md` and `DEPLOYMENT.md`. The business rules in those documents and in
`PRODUCT-BEHAVIOR-MATRIX.md` and `SECURITY.md` still apply.

## Decision

- **One Spring Boot application** (modular monolith, packages per feature) instead of separate services. Modules talk
  through Spring application events (`PaymentSucceeded` → `OrderPaid` → delivery / notifications) so they can be
  split later if needed.
- **Plain HTML/CSS/JavaScript** served by the same application instead of Next.js. No build step; strict CSP (no inline
  scripts or styles); all text inserted with `textContent`.
- **MySQL 8** (already installed on the target machine) with Flyway migrations, instead of PostgreSQL.
- **Demo payment provider** until Razorpay is integrated. It implements the same `PaymentProvider` interface, is
  labelled in the UI, and refuses to start under the `prod` profile.
- **SMTP per storefront, configured in the admin panel** (password encrypted with AES-GCM), not via environment
  variables, so each storefront can send from its own address.

## Why

The owner chose this stack to reduce moving parts and delivery time: one deployable, one database, no Node toolchain.

## Consequences

- Storefront isolation is enforced in code (separate admin session realms, every query scoped by storefront)
  rather than by separate services and databases.
- Scaling is vertical / multiple identical instances behind a load balancer. Scheduled jobs (reservation expiry) are
  safe to run on several instances because each order is locked before it is expired.
