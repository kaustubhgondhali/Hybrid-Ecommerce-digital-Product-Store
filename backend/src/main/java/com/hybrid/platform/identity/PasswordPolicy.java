package com.hybrid.platform.identity;

import com.hybrid.platform.common.error.ApiException;

public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    public static void check(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw ApiException.rule("WEAK_PASSWORD", "Password must be 8 to 128 characters");
        }
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        if (!letter || !digit) {
            throw ApiException.rule("WEAK_PASSWORD", "Password must contain at least one letter and one number");
        }
    }
}
