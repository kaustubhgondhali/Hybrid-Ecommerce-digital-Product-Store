package com.hybrid.platform.delivery;

import com.hybrid.platform.catalog.CatalogEnums.DeliveryKind;
import com.hybrid.platform.delivery.DeliveryRepositories.AssetRepository;
import com.hybrid.platform.delivery.DeliveryRepositories.LicenseKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only delivery facts used by catalog (publish gate) and checkout (license availability). */
@Service
public class DeliveryReadinessService {

    private final AssetRepository assets;
    private final LicenseKeyRepository licenseKeys;

    public DeliveryReadinessService(AssetRepository assets, LicenseKeyRepository licenseKeys) {
        this.assets = assets;
        this.licenseKeys = licenseKeys;
    }

    @Transactional(readOnly = true)
    public boolean isReady(Long productId, DeliveryKind kind) {
        if (kind == null) {
            return false;
        }
        boolean hasFile = assets.existsByProductIdAndStatus(productId, DigitalAsset.Status.READY);
        boolean hasKeys = availableLicenses(productId) > 0;
        return switch (kind) {
            case DOWNLOAD -> hasFile;
            case LICENSE_KEY -> hasKeys;
            case DOWNLOAD_AND_LICENSE -> hasFile && hasKeys;
        };
    }

    @Transactional(readOnly = true)
    public long availableLicenses(Long productId) {
        return licenseKeys.countByProductIdAndStatus(productId, LicenseKey.Status.AVAILABLE);
    }

    public static boolean needsLicense(DeliveryKind kind) {
        return kind == DeliveryKind.LICENSE_KEY || kind == DeliveryKind.DOWNLOAD_AND_LICENSE;
    }
}
