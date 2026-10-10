# Security Architecture

Status: **Proposed (pre-implementation)**

---

## 1. Trust model

| Zone | Trust | Notes |
|---|---|---|
| Browser / Next.js client code | **Untrusted** | Anything it sends may be forged |
| Next.js server (SSR, route handlers) | Untrusted for authorization | Acts on behalf of the user with the user's token; holds no privileged credentials |
| Edge proxy | Transport only | TLS termination, path routing, request size limits |
| API Gateway | First-line policy | Not the final authority; services re-check everything |
| Services | Authoritative | Each validates JWT, audience, storefront, permission, and resource ownership |
| Third-party callbacks | Untrusted until verified | Signature + timestamp + dedupe |

**The frontend is never trusted for:** price, stock, payment success, order status, permissions, digital entitlement, affiliate validation. Request DTOs physically lack those fields; the server derives them.

## 2. Authentication

### 2.1 Customers

- Email + password. Passwords hashed with **Argon2id** (memory-hard parameters tuned to ~250 ms on prod hardware); breached-password check (k-anonymity range API) at registration/reset.
- Email verification required before checkout.
- Login throttling: per-account and per-IP counters in Redis; progressive delay, temporary lock, generic error messages (no account enumeration).
- Optional TOTP MFA for customers (later phase).

### 2.2 Admins (per storefront)

- Separate account tables per storefront scope (`admin_users.storefront`). A shop admin account cannot authenticate at `/digital/admin` and vice versa — the login endpoint itself is storefront-specific and looks up `(storefront, email)`.
- **TOTP MFA mandatory.** Login is two-step: password → short-lived MFA challenge → tokens. First login forces password change and MFA enrollment (pre-MFA token can only call enrollment endpoints).
- Invitations: single-use, hashed, 72h expiry, bound to storefront and email.
- Sessions: shorter lifetimes than customers, idle timeout, absolute timeout (e.g. 12h).
- Role or status change → all sessions revoked immediately (Redis revocation list + refresh token family revoked).
- **Bootstrap** of the first Master Admin per storefront: operator-run CLI command inside the identity-service container (`bootstrap-admin --storefront=SHOP --email=…`), audited, refuses to run if an active Master Admin already exists for that storefront. No HTTP bootstrap endpoint exists.

### 2.3 Tokens

| Token | Format | Lifetime | Storage |
|---|---|---|---|
| Customer access | JWT RS256 | 10 min | `__Host-cust_at` HttpOnly, Secure, SameSite=Lax, Path=/ |
| Customer refresh | Opaque 256-bit, hashed in DB, rotating with reuse detection | 30 days sliding | `__Secure-cust_rt` HttpOnly, Secure, SameSite=Strict, Path=/api/v1/auth/customer |
| Admin access | JWT RS256 | 5 min | `__Host-{shop\|dig}_adm_at` HttpOnly, Secure, SameSite=Strict |
| Admin refresh | Opaque, rotating | 12h absolute, 30 min idle | `__Secure-{shop\|dig}_adm_rt`, Path=/api/v1/auth/admin/{storefront} |
| Service | JWT RS256 via client credentials | 5 min | In-memory in calling service |

JWT claims:

```json
{
  "iss": "https://<domain>/identity",
  "sub": "<user uuid>",
  "aud": "shop-admin",
  "sid": "<session uuid>",
  "typ": "ADMIN",
  "storefront": "SHOP",
  "roles": ["CATALOG_MANAGER"],
  "perms": ["catalog:product:read", "catalog:product:write"],
  "amr": ["pwd", "otp"],
  "iat": 0, "exp": 0, "jti": "<uuid>"
}
```

- Tokens are never placed in `localStorage`, `sessionStorage`, URLs, or logs.
- Refresh token reuse (a rotated token presented again) revokes the whole family and forces re-login.
- Signing keys rotate; JWKS exposes current + previous `kid`. Private keys come from a secret store, not the image.

## 3. Authorization

### 3.1 Layers

1. **Gateway:** audience must match route surface; for admin routes `storefront` claim must equal the `{storefront}` path segment; session not revoked.
2. **Service security filter (shared `platform-security` lib):** re-validates JWT (signature, `exp`, `aud`, `iss`); builds a principal with `subjectType`, `storefront`, `perms`.
3. **Method security:** `@PreAuthorize("@sf.matches(#storefront) and hasAuthority('catalog:product:write')")`.
4. **Data layer:** every admin repository query includes `storefront = :principalStorefront`; every customer query includes `customer_id = :principalId`. Not-owned resources return `404`.

### 3.2 Roles (per storefront, seeded)

| Role | Typical permissions |
|---|---|
| `MASTER_ADMIN` | All permissions of that storefront, incl. staff & role management |
| `CATALOG_MANAGER` | `catalog:*`, `delivery:asset:*` (digital), `delivery:policy:*` |
| `ORDER_MANAGER` | `commerce:order:*`, `commerce:refund:create`, `payment:read` |
| `FULFILLMENT` | `commerce:order:read`, `commerce:fulfillment:*`, `commerce:inventory:*` |
| `FINANCE` | `commerce:invoice:read`, `payment:read`, `affiliate:report:read` |
| `AFFILIATE_MANAGER` | `affiliate:*`, `catalog:product:read` |
| `SUPPORT` | Read orders, resend notifications, view entitlements |
| `CONTENT` | `notification:template:*`, catalog content fields |

Escalation rules:

- An admin can only grant permissions they themselves hold.
- Only `MASTER_ADMIN` can create/modify roles and assign `MASTER_ADMIN`.
- The last active `MASTER_ADMIN` of a storefront cannot be disabled or demoted.
- No permission string contains or implies another storefront; the storefront comes solely from the account record.

### 3.3 Service-to-service

- Internal endpoints (`/internal/**`) require a client-credentials token with a specific scope (e.g. `catalog.pricing.quote`), granted per calling client (commerce may quote prices; nobody else).
- Gateway blocks `/internal/**`; services also bind internal endpoints to require `aud=internal`.
- Services never trust identity headers like `X-User-Id`; identity is always derived from a verified token.
- Future hardening: mTLS between services (service mesh or Compose-level certs).

## 4. Web application security

| Threat | Control |
|---|---|
| XSS | React escaping; no `dangerouslySetInnerHTML` except sanitized (DOMPurify server-side) rich text from admins; strict CSP with nonces; HttpOnly cookies so tokens can't be read by script |
| CSRF | SameSite cookies + double-submit CSRF token header (`X-CSRF-Token`) required on all cookie-authenticated non-GET requests, verified at gateway |
| Clickjacking | `frame-ancestors 'none'` / `X-Frame-Options: DENY` |
| CORS | Same-origin only; gateway rejects cross-origin credentialed requests |
| Transport | HTTPS only, HSTS (preload after stabilization), TLS 1.2+ |
| Headers | `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy` minimal |
| IDOR | Ownership predicates in queries; UUIDs (non-sequential) as identifiers; `404` on not-owned |
| Mass assignment | Explicit request DTOs per endpoint; never bind to entities |
| Injection | JPA parameter binding / Criteria API only; no string-built SQL; validation with Bean Validation |
| Open redirect | `/go/{code}` resolves only server-stored destinations whose host matches merchant allowlist; login `returnTo` restricted to same-origin relative paths |
| SSRF | Services never fetch user-supplied URLs. Affiliate URLs are redirected to, never fetched. Image/asset uploads go direct to storage via presigned URLs |
| Admin path discovery | `/shop/admin` and `/digital/admin` are not secret; security relies on auth, MFA and rate limiting, not obscurity |

## 5. Commerce-specific controls

| Risk | Control |
|---|---|
| Price tampering | Cart stores only variant ID + quantity. Checkout re-fetches prices from catalog internal API and recomputes totals. Request bodies have no price fields |
| Stock manipulation / overselling | Atomic conditional reservation in DB; quantity caps per line; reservation TTL |
| Fake payment success | Only `payment-service` webhooks (signature verified) or provider API reconciliation can mark payment succeeded. Provider return URL is ignored as evidence |
| Webhook replay | Provider signature + timestamp tolerance + unique `(provider, provider_event_id)` |
| Double charge / double order | `Idempotency-Key` on checkout; one payment per order (`UNIQUE (order_id)`) |
| Amount mismatch | Payment-service verifies webhook amount + currency equal the recorded payment before emitting success; mismatch → alert, no fulfillment |
| Refund abuse | Permission-gated, Idempotency-Key, sum of refunds ≤ captured amount, audited |
| Affiliate product purchase | Commerce rejects non-DIRECT lines at add-to-cart and again at checkout; DB `CHECK (selling_method = 'DIRECT')` on order lines |
| Affiliate click fraud | Rate limits on `/go/*`, bot heuristics, click de-dup window, suspected-bot flag excluded from reports |
| Fake conversions | HMAC-signed postbacks with per-network secret, optional IP allowlist, unique external transaction ID, conversions start as `PENDING` |

## 6. Digital file security

- Sellable files live only in the private bucket; bucket policy denies public reads and listing.
- Uploads: admin requests an upload session with declared size + SHA-256; presigned PUT is scoped to one object key, size-limited, short TTL. On completion the service verifies size and checksum, sniffs magic bytes against an allowlist of MIME types, and passes the object through a malware-scan hook (ClamAV container initially) before `READY`.
- Downloads: per-request presigned GET (TTL ≈ 2–5 min), `Content-Disposition: attachment`, issued only after checking entitlement `ACTIVE`, not expired, download limit not exceeded. Every issuance is logged.
- Leaked-link mitigation: short TTL, per-entitlement download limits, anomaly alert on high issuance rate per entitlement. (Watermarking per buyer is a possible later enhancement.)
- License keys: encrypted with AES-256-GCM using a data key from the secret store / KMS; uniqueness enforced via SHA-256 fingerprint; decrypted only when the owning customer views them.

## 7. Secrets and data protection

- Secrets (DB passwords, JWT signing keys, provider API keys, webhook secrets, encryption keys) are injected via Docker secrets / cloud secret manager — never committed, never baked into images. `.env` files only for local development and git-ignored.
- PII minimization: affiliate click IPs and user agents stored as salted hashes; logs exclude emails, addresses, tokens, keys.
- Encryption at rest: managed PostgreSQL + storage encryption in production; application-level encryption for MFA secrets, license keys, network secrets.
- Privacy rights (export / erasure) supported via identity-service orchestration (see [DATABASE.md §4](./DATABASE.md#4-operations)). Applicable regimes (e.g. India DPDP Act, GDPR for EU customers) to be confirmed.
- PCI DSS: hosted payment fields/checkout keep the platform in SAQ-A scope. No card data in logs, DB, or memory.

## 8. Rate limiting (initial values, tunable)

| Route | Limit |
|---|---|
| Customer login | 5/min per account, 20/min per IP |
| Admin login + MFA | 5/min per account, 10/min per IP; lock after 10 failures in 15 min |
| Password reset request | 3/hour per account, 10/hour per IP |
| Checkout | 10/min per customer |
| `/go/{code}` | 60/min per IP |
| Download link issuance | 30/hour per entitlement |
| Webhooks / postbacks | 300/min per source (protect against floods; legit retries still accepted) |
| Public catalog | 300/min per IP |

## 9. Audit and monitoring

- Every admin mutation writes to the owning service's append-only `admin_audit_log` (actor, storefront, action, before/after, request ID).
- Security events (failed logins, MFA failures, refresh token reuse, permission denials, webhook signature failures, amount mismatches) are logged with a `security=true` marker and alerted on thresholds.
- Dependency and image scanning in CI (OWASP Dependency-Check / Trivy); SAST (e.g. Semgrep); secret scanning on commits.
- Annual (or pre-launch) external penetration test.
