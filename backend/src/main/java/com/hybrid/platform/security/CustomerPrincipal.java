package com.hybrid.platform.security;

import java.io.Serializable;

public record CustomerPrincipal(Long id, String email, String fullName) implements Serializable {
}
