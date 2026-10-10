package com.hybrid.platform.delivery;

import com.hybrid.platform.catalog.CatalogEnums.ProductType;
import com.hybrid.platform.catalog.CatalogEnums.SellingMethod;
import com.hybrid.platform.catalog.CatalogQueryService;
import com.hybrid.platform.common.AppProperties;
import com.hybrid.platform.common.PageResponse;
import com.hybrid.platform.common.Storefront;
import com.hybrid.platform.common.audit.AuditService;
import com.hybrid.platform.common.crypto.CryptoService;
import com.hybrid.platform.common.crypto.Hashing;
import com.hybrid.platform.common.error.ApiException;
import com.hybrid.platform.commerce.order.OrderEvents;
import com.hybrid.platform.delivery.DeliveryRepositories.*;
import com.hybrid.platform.identity.CustomerRepository;
import com.hybrid.platform.security.AdminPrincipal;
import com.hybrid.platform.storage.FileStorage;
import com.hybrid.platform.storage.FileTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    public record AssetView(Long id, String fileName, String contentType, long sizeBytes, String sha256,
                            String versionLabel, String status, Instant createdAt) {
    }

    public record LibraryItem(Long entitlementId, String storefront, Long productId, String productTitle,
                              String status, Instant grantedAt, Instant expiresAt, Integer downloadLimit,
                              int downloadsUsed, boolean usable, List<AssetView> files, List<String> licenseKeys) {
    }

    public record DownloadLink(String url, Instant expiresAt) {
    }

    public record DownloadFile(Path path, String fileName, String contentType, long sizeBytes) {
    }

    public record PolicyView(Long productId, Integer downloadLimit, Integer accessDays, long availableLicenseKeys,
                             List<AssetView> assets) {
    }

    public record EntitlementAdminView(Long id, Long customerId, String customerEmail, Long productId,
                                       String productTitle, Long orderId, String source, String status,
                                       Instant grantedAt, Instant expiresAt, Integer downloadLimit, int downloadsUsed,
                                       String revokeReason) {
    }

    public record ImportKeysResult(int imported, int duplicates, int rejected) {
    }

    private final AssetRepository assets;
    private final PolicyRepository policies;
    private final LicenseKeyRepository licenseKeys;
    private final EntitlementRepository entitlements;
    private final DownloadTokenRepository tokens;
    private final CatalogQueryService catalog;
    private final CustomerRepository customers;
    private final FileStorage storage;
    private final CryptoService crypto;
    private final AuditService audit;
    private final AppProperties props;

    public DeliveryService(AssetRepository assets, PolicyRepository policies, LicenseKeyRepository licenseKeys,
                           EntitlementRepository entitlements, DownloadTokenRepository tokens,
                           CatalogQueryService catalog, CustomerRepository customers, FileStorage storage,
                           CryptoService crypto, AuditService audit, AppProperties props) {
        this.assets = assets;
        this.policies = policies;
        this.licenseKeys = licenseKeys;
        this.entitlements = entitlements;
        this.tokens = tokens;
        this.catalog = catalog;
        this.customers = customers;
        this.storage = storage;
        this.crypto = crypto;
        this.audit = audit;
        this.props = props;
    }

    // ------------------------------------------------------------------ order lifecycle

    @EventListener
    @Transactional
    public void onOrderPaid(OrderEvents.OrderPaid event) {
        for (OrderEvents.DigitalLine line : event.digitalLines()) {
            if (entitlements.existsByOrderLineId(line.orderLineId())) {
                continue;
            }
            DeliveryPolicy policy = policies.findById(line.productId()).orElse(new DeliveryPolicy(line.productId(), event.storefront()));
            Instant expires = policy.getAccessDays() == null ? null : Instant.now().plus(Duration.ofDays(policy.getAccessDays()));
            Entitlement e = entitlements.save(new Entitlement(event.storefront(), event.customerId(), line.productId(),
                    line.title(), event.orderId(), line.orderLineId(), Entitlement.Source.PURCHASE,
                    policy.getDownloadLimit(), expires));
            assignLicenseKeys(e, line.productId(), line.quantity());
        }
    }

    @EventListener
    @Transactional
    public void onOrderRefunded(OrderEvents.OrderRefunded event) {
        if (!event.full()) {
            return;
        }
        for (Entitlement e : entitlements.findByOrderId(event.orderId())) {
            if (e.getStatus() == Entitlement.Status.ACTIVE) {
                e.revoke("Order refunded");
                licenseKeys.findByEntitlementId(e.getId()).forEach(k -> k.setStatus(LicenseKey.Status.REVOKED));
            }
        }
    }

    private void assignLicenseKeys(Entitlement e, Long productId, int quantity) {
        var product = catalog.product(productId);
        if (product.isEmpty() || !DeliveryReadinessService.needsLicense(product.get().deliveryKind())) {
            return;
        }
        List<LicenseKey> keys = licenseKeys.lockAvailable(productId, quantity);
        keys.forEach(k -> k.assign(e.getId()));
        if (keys.size() < quantity) {
            log.warn("Entitlement {} for product {} received {} of {} license keys; admin action required",
                    e.getId(), productId, keys.size(), quantity);
        }
    }

    // ------------------------------------------------------------------ customer library

    @Transactional(readOnly = true)
    public List<LibraryItem> library(Long customerId) {
        Instant now = Instant.now();
        List<LibraryItem> items = new ArrayList<>();
        for (Entitlement e : entitlements.findByCustomerIdOrderByGrantedAtDesc(customerId)) {
            boolean usable = e.isUsable(now);
            List<AssetView> files = usable
                    ? assets.findByProductIdAndStatusOrderByCreatedAtDesc(e.getProductId(), DigitalAsset.Status.READY)
                    .stream().map(DeliveryService::toView).toList()
                    : List.of();
            List<String> keys = usable
                    ? licenseKeys.findByEntitlementId(e.getId()).stream()
                    .filter(k -> k.getStatus() == LicenseKey.Status.ASSIGNED)
                    .map(k -> crypto.decrypt(k.getKeyEnc())).toList()
                    : List.of();
            items.add(new LibraryItem(e.getId(), e.getStorefront().name(), e.getProductId(), e.getProductTitle(),
                    e.getStatus().name(), e.getGrantedAt(), e.getExpiresAt(), e.getDownloadLimit(), e.getDownloadsUsed(),
                    usable, files, keys));
        }
        return items;
    }

    @Transactional
    public DownloadLink createDownloadLink(Long customerId, Long entitlementId, Long assetId) {
        Entitlement e = entitlements.findByIdAndCustomerId(entitlementId, customerId)
                .orElseThrow(() -> ApiException.notFound("ENTITLEMENT_NOT_FOUND", "Item not found in your library"));
        if (!e.isUsable(Instant.now())) {
            throw ApiException.forbidden("ENTITLEMENT_INACTIVE", "Access to this item has ended");
        }
        if (!e.hasDownloadsLeft()) {
            throw ApiException.forbidden("DOWNLOAD_LIMIT_REACHED", "You have reached the download limit for this item");
        }
        DigitalAsset asset = assets.findById(assetId)
                .filter(a -> a.getProductId().equals(e.getProductId()) && a.getStatus() == DigitalAsset.Status.READY)
                .orElseThrow(() -> ApiException.notFound("FILE_NOT_FOUND", "File not found"));
        e.countDownload();
        String raw = Hashing.randomToken();
        Instant expires = Instant.now().plusSeconds(props.delivery().downloadLinkSeconds());
        tokens.save(new DownloadToken(Hashing.sha256Hex(raw), e.getId(), asset.getId(), expires));
        return new DownloadLink("/api/downloads/" + raw, expires);
    }

    @Transactional(readOnly = true)
    public DownloadFile resolveDownload(Long customerId, String rawToken) {
        DownloadToken t = tokens.findByTokenHash(Hashing.sha256Hex(rawToken))
                .filter(x -> x.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> ApiException.notFound("DOWNLOAD_EXPIRED", "This download link has expired. Request a new one from your library."));
        Entitlement e = entitlements.findByIdAndCustomerId(t.getEntitlementId(), customerId)
                .filter(x -> x.isUsable(Instant.now()))
                .orElseThrow(() -> ApiException.notFound("DOWNLOAD_EXPIRED", "This download link is not valid for your account"));
        DigitalAsset asset = assets.findById(t.getAssetId())
                .filter(a -> a.getProductId().equals(e.getProductId()))
                .orElseThrow(() -> ApiException.notFound("FILE_NOT_FOUND", "File not found"));
        return new DownloadFile(storage.resolve(FileStorage.Area.PRIVATE, asset.getStorageKey()), asset.getFileName(),
                asset.getContentType(), asset.getSizeBytes());
    }

    // ------------------------------------------------------------------ admin: assets, keys, policy

    @Transactional
    public AssetView uploadAsset(AdminPrincipal admin, Long productId, MultipartFile file, String versionLabel) throws IOException {
        requireDigitalDirect(admin.storefront(), productId);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("FILE_REQUIRED", "Choose a file to upload");
        }
        String original = sanitizeFileName(file.getOriginalFilename());
        byte[] header;
        try (InputStream in = file.getInputStream()) {
            header = in.readNBytes(16);
        }
        String ext = FileTypes.requireDigitalFile(original, header);
        FileStorage.StoredFile stored;
        try (InputStream in = file.getInputStream()) {
            stored = storage.store(FileStorage.Area.PRIVATE, in, ext, file.getSize() + 1);
        }
        DigitalAsset asset = assets.save(new DigitalAsset(admin.storefront(), productId, original, stored.key(),
                FileTypes.contentType(ext), stored.sizeBytes(), stored.sha256(),
                versionLabel == null || versionLabel.isBlank() ? null : versionLabel.trim(), admin.id()));
        audit.record(admin, "DIGITAL_ASSET_UPLOADED", "Product", productId, original + " sha256=" + stored.sha256());
        return toView(asset);
    }

    @Transactional
    public void retireAsset(AdminPrincipal admin, Long assetId) {
        DigitalAsset asset = assets.findByIdAndStorefront(assetId, admin.storefront())
                .orElseThrow(() -> ApiException.notFound("FILE_NOT_FOUND", "File not found"));
        asset.setStatus(DigitalAsset.Status.RETIRED);
        audit.record(admin, "DIGITAL_ASSET_RETIRED", "DigitalAsset", assetId, asset.getFileName());
    }

    @Transactional
    public ImportKeysResult importLicenseKeys(AdminPrincipal admin, Long productId, String keysText) {
        requireDigitalDirect(admin.storefront(), productId);
        int imported = 0, duplicates = 0, rejected = 0;
        Set<String> seen = new HashSet<>();
        String[] lines = keysText == null ? new String[0] : keysText.split("\\r?\\n");
        if (lines.length > 10000) {
            throw ApiException.rule("IMPORT_TOO_LARGE", "Import at most 10,000 keys at a time");
        }
        for (String line : lines) {
            String key = line.trim();
            if (key.isEmpty()) continue;
            if (key.length() > 500) { rejected++; continue; }
            String fp = Hashing.sha256Hex(key);
            if (!seen.add(fp) || licenseKeys.existsByProductIdAndFingerprint(productId, fp)) { duplicates++; continue; }
            licenseKeys.save(new LicenseKey(admin.storefront(), productId, crypto.encrypt(key), fp));
            imported++;
        }
        audit.record(admin, "LICENSE_KEYS_IMPORTED", "Product", productId, "imported=" + imported + " duplicates=" + duplicates);
        return new ImportKeysResult(imported, duplicates, rejected);
    }

    @Transactional(readOnly = true)
    public PolicyView policy(Storefront sf, Long productId) {
        requireDigitalDirect(sf, productId);
        DeliveryPolicy p = policies.findById(productId).orElse(new DeliveryPolicy(productId, sf));
        return new PolicyView(productId, p.getDownloadLimit(), p.getAccessDays(),
                licenseKeys.countByProductIdAndStatus(productId, LicenseKey.Status.AVAILABLE),
                assets.findByProductIdOrderByCreatedAtDesc(productId).stream().map(DeliveryService::toView).toList());
    }

    @Transactional
    public PolicyView updatePolicy(AdminPrincipal admin, Long productId, Integer downloadLimit, Integer accessDays) {
        requireDigitalDirect(admin.storefront(), productId);
        if ((downloadLimit != null && downloadLimit < 1) || (accessDays != null && accessDays < 1)) {
            throw ApiException.rule("INVALID_POLICY", "Limits must be at least 1, or empty for unlimited");
        }
        DeliveryPolicy p = policies.findById(productId).orElse(new DeliveryPolicy(productId, admin.storefront()));
        p.setDownloadLimit(downloadLimit);
        p.setAccessDays(accessDays);
        policies.save(p);
        audit.record(admin, "DELIVERY_POLICY_UPDATED", "Product", productId, "limit=" + downloadLimit + " days=" + accessDays);
        return policy(admin.storefront(), productId);
    }

    // ------------------------------------------------------------------ admin: entitlements

    @Transactional(readOnly = true)
    public PageResponse<EntitlementAdminView> listEntitlements(Storefront sf, String customerEmail, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        var result = (customerEmail == null || customerEmail.isBlank())
                ? entitlements.findByStorefrontOrderByGrantedAtDesc(sf, pr)
                : customers.findByEmailIgnoreCase(customerEmail.trim())
                .map(c -> entitlements.findByStorefrontAndCustomerIdOrderByGrantedAtDesc(sf, c.getId(), pr))
                .orElse(org.springframework.data.domain.Page.empty(pr));
        Map<Long, String> emails = new HashMap<>();
        customers.findAllById(result.getContent().stream().map(Entitlement::getCustomerId).distinct().toList())
                .forEach(c -> emails.put(c.getId(), c.getEmail()));
        return PageResponse.of(result, e -> new EntitlementAdminView(e.getId(), e.getCustomerId(),
                emails.getOrDefault(e.getCustomerId(), "?"), e.getProductId(), e.getProductTitle(), e.getOrderId(),
                e.getSource().name(), e.getStatus().name(), e.getGrantedAt(), e.getExpiresAt(), e.getDownloadLimit(),
                e.getDownloadsUsed(), e.getRevokeReason()));
    }

    @Transactional
    public void grantManually(AdminPrincipal admin, String customerEmail, Long productId, String reason) {
        var product = requireDigitalDirect(admin.storefront(), productId);
        var customer = customers.findByEmailIgnoreCase(customerEmail.trim())
                .orElseThrow(() -> ApiException.notFound("CUSTOMER_NOT_FOUND", "No customer with this email"));
        DeliveryPolicy policy = policies.findById(productId).orElse(new DeliveryPolicy(productId, admin.storefront()));
        Instant expires = policy.getAccessDays() == null ? null : Instant.now().plus(Duration.ofDays(policy.getAccessDays()));
        Entitlement e = entitlements.save(new Entitlement(admin.storefront(), customer.getId(), productId, product.title(),
                null, null, Entitlement.Source.ADMIN_GRANT, policy.getDownloadLimit(), expires));
        assignLicenseKeys(e, productId, 1);
        audit.record(admin, "ENTITLEMENT_GRANTED", "Entitlement", e.getId(), "customer=" + customer.getEmail() + " reason=" + reason);
    }

    @Transactional
    public void revoke(AdminPrincipal admin, Long entitlementId, String reason) {
        Entitlement e = entitlements.findByIdAndStorefront(entitlementId, admin.storefront())
                .orElseThrow(() -> ApiException.notFound("ENTITLEMENT_NOT_FOUND", "Entitlement not found"));
        e.revoke(reason == null || reason.isBlank() ? "Revoked by admin" : reason.trim());
        licenseKeys.findByEntitlementId(e.getId()).forEach(k -> k.setStatus(LicenseKey.Status.REVOKED));
        audit.record(admin, "ENTITLEMENT_REVOKED", "Entitlement", entitlementId, reason);
    }

    // ------------------------------------------------------------------ helpers

    private CatalogQueryService.ProductRef requireDigitalDirect(Storefront sf, Long productId) {
        var product = catalog.requireProduct(sf, productId);
        if (product.productType() != ProductType.DIGITAL || product.sellingMethod() != SellingMethod.DIRECT) {
            throw ApiException.rule("NOT_DIGITAL_DIRECT", "Files and license keys apply only to DIGITAL + DIRECT products");
        }
        return product;
    }

    private static String sanitizeFileName(String name) {
        String base = name == null ? "file" : name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (base.isEmpty()) base = "file";
        return base.length() > 200 ? base.substring(base.length() - 200) : base;
    }

    private static AssetView toView(DigitalAsset a) {
        return new AssetView(a.getId(), a.getFileName(), a.getContentType(), a.getSizeBytes(), a.getSha256(),
                a.getVersionLabel(), a.getStatus().name(), a.getCreatedAt());
    }
}
