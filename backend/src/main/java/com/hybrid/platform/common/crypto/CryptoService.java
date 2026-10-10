package com.hybrid.platform.common.crypto;

import com.hybrid.platform.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * AES-256-GCM for secrets stored in the database (SMTP passwords, MFA secrets, license keys)
 * and salted hashing for pseudonymised values (affiliate click IPs).
 */
@Service
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final String hashSalt;

    public CryptoService(AppProperties props, Environment env) {
        boolean prod = env.acceptsProfiles(Profiles.of("prod"));
        this.key = new SecretKeySpec(resolveKey(props.encryptionKey(), prod), "AES");
        String salt = props.hashSalt();
        if (salt == null || salt.isBlank()) {
            if (prod) {
                throw new IllegalStateException("APP_HASH_SALT must be set in production");
            }
            salt = Hashing.randomToken();
        }
        this.hashSalt = salt;
    }

    private static byte[] resolveKey(String configured, boolean prod) {
        if (configured == null || configured.isBlank()) {
            if (prod) {
                throw new IllegalStateException("APP_ENCRYPTION_KEY (base64, 32 bytes) must be set in production");
            }
            log.warn("APP_ENCRYPTION_KEY is not set: using a temporary key. Encrypted values (SMTP password, MFA, "
                    + "license keys) will be unreadable after restart. Set APP_ENCRYPTION_KEY in .env.");
            return Hashing.randomBytes(32);
        }
        byte[] decoded = Base64.getDecoder().decode(configured.trim());
        if (decoded.length != 32) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY must decode to exactly 32 bytes");
        }
        return decoded;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = Hashing.randomBytes(IV_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_LENGTH));
            return new String(cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Stored secret cannot be decrypted (was APP_ENCRYPTION_KEY changed?)", e);
        }
    }

    public String saltedHash(String value) {
        return value == null ? null : Hashing.sha256Hex(hashSalt + ":" + value);
    }
}
