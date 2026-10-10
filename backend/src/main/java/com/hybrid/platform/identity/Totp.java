package com.hybrid.platform.identity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;

/** RFC 6238 TOTP (SHA-1, 30 s, 6 digits) compatible with Google Authenticator, Authy, Microsoft Authenticator. */
public final class Totp {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    public static boolean verify(String base32Secret, String code, Instant now) {
        if (code == null || !code.matches("\\d{6}")) {
            return false;
        }
        byte[] key = base32Decode(base32Secret);
        long step = now.getEpochSecond() / 30;
        for (long offset = -1; offset <= 1; offset++) {
            if (generate(key, step + offset).equals(code)) {
                return true;
            }
        }
        return false;
    }

    static String generate(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    public static byte[] base32Decode(String s) {
        String clean = s.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int val = BASE32.indexOf(c);
            if (val < 0) {
                throw new IllegalArgumentException("Invalid base32");
            }
            buffer = (buffer << 5) | val;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xFF));
                bits -= 8;
            }
        }
        return out.array();
    }
}
