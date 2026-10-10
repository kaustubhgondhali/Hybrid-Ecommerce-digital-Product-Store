package com.hybrid.platform.ops;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorLogServiceTest {

    @Test
    void numericIdsAndQueryStringsAreRemoved() {
        assertThat(ErrorLogService.normalisePath("/api/shop/admin/orders/123/ship?x=1"))
                .isEqualTo("/api/shop/admin/orders/{id}/ship");
        assertThat(ErrorLogService.normalisePath("/api/shop/admin/products/7/images/42"))
                .isEqualTo("/api/shop/admin/products/{id}/images/{id}");
        assertThat(ErrorLogService.normalisePath("/a/1/2/3")).isEqualTo("/a/{id}/{id}/{id}");
    }

    @Test
    void longTokensAreNotStored() {
        assertThat(ErrorLogService.normalisePath("/api/auth/reset/AbCdEfGhIjKlMnOpQrStUvWx_123"))
                .isEqualTo("/api/auth/reset/{id}");
    }

    @Test
    void shortSlugsAreKept() {
        assertThat(ErrorLogService.normalisePath("/api/shop/catalog/products/green-tea"))
                .isEqualTo("/api/shop/catalog/products/green-tea");
        assertThat(ErrorLogService.normalisePath(null)).isNull();
    }
}
