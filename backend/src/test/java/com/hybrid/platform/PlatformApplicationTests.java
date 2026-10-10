package com.hybrid.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformApplicationTests {

    @Autowired
    MockMvc mvc;

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void publicCatalogIsReadableAnonymously() throws Exception {
        mvc.perform(get("/api/shop/catalog/products")).andExpect(status().isOk());
        mvc.perform(get("/api/digital/catalog/store")).andExpect(status().isOk())
                .andExpect(jsonPath("$.demoPayments").value(true));
    }

    @Test
    void adminApiRequiresAdminSession() throws Exception {
        mvc.perform(get("/api/shop/admin/products")).andExpect(status().isUnauthorized());
    }
}
