package com.hybrid.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sign-in state is kept in the SPRING_SESSION tables and found again through the SESSION cookie. */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JdbcSessionIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void customerSessionIsStoredInTheDatabase() throws Exception {
        MvcResult registered = mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("email", "session-user@test.local", "password", "Session-Passw0rd1",
                                "fullName", "Session User", "storefront", "shop"))))
                .andExpect(status().isCreated())
                .andReturn();
        Cookie session = registered.getResponse().getCookie("SESSION");
        assertThat(session).as("SESSION cookie").isNotNull();
        assertThat(session.isHttpOnly()).isTrue();

        Integer attributes = jdbc.queryForObject(
                "select count(*) from SPRING_SESSION_ATTRIBUTES where ATTRIBUTE_NAME = 'HPP_CTX_CUSTOMER'", Integer.class);
        assertThat(attributes).isPositive();

        mvc.perform(get("/api/auth/me").cookie(session).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("session-user@test.local"));

        mvc.perform(get("/api/auth/me").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
