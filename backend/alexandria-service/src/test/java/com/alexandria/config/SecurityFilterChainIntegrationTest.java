package com.alexandria.config;

import com.alexandria.organization.OrganizationRepository;
import com.alexandria.user.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the security filter chain configuration.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Public endpoints (actuator/health) are accessible without authentication</li>
 *   <li>Protected endpoints require a valid JWT</li>
 *   <li>User provisioning occurs on first valid JWT request</li>
 *   <li>Email claim validation rejects missing/invalid email with 403</li>
 * </ul>
 *
 * <p>Uses Testcontainers for PostgreSQL and spring-security-test JWT mocking.</p>
 *
 * <p><b>Validates: Requirements 3.1–3.5, 4.1, 4.4, 4.7</b></p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(SecurityFilterChainIntegrationTest.JwtDecoderTestConfig.class)
class SecurityFilterChainIntegrationTest {

    private static final UUID DEFAULT_ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeAll
    void setUpMockMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        // Ensure the default org exists using JDBC to avoid @GeneratedValue issues
        if (!organizationRepository.existsById(DEFAULT_ORG_ID)) {
            jdbcTemplate.update(
                    "INSERT INTO organizations (id, name, slug, created_at, updated_at) VALUES (?, ?, ?, NOW(), NOW())",
                    DEFAULT_ORG_ID, "Test Organization", "test-org");
        }
    }

    @BeforeEach
    void setUp() {
        // Clean up audit events referencing users, then users between tests
        jdbcTemplate.update("DELETE FROM audit_events WHERE organization_id = ?", DEFAULT_ORG_ID);
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Unauthenticated requests to /actuator/health succeed")
    void unauthenticatedRequestToActuatorHealthSucceeds() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Unauthenticated requests to /api/users get 401")
    void unauthenticatedRequestToApiUsersGets401() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Requests with invalid JWT get 401")
    void requestWithInvalidJwtGets401() throws Exception {
        // Sending a request with a raw invalid Authorization header
        mockMvc.perform(get("/api/users")
                        .header("Authorization", "Bearer invalid.token.here"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("User provisioning occurs on first valid JWT request")
    void userProvisioningOnFirstValidJwtRequest() throws Exception {
        String subject = "auth0|user123";
        String email = "newuser@example.com";

        // Verify no users exist initially
        assertThat(userRepository.findByOrganizationIdAndExternalSubject(DEFAULT_ORG_ID, subject))
                .isEmpty();

        // Make a request with a valid JWT — user should be auto-provisioned
        mockMvc.perform(get("/api/me")
                        .with(jwt().jwt(builder -> builder
                                .subject(subject)
                                .claim("email", email)
                                .issuer("https://issuer.example.com")
                                .issuedAt(Instant.now())
                                .expiresAt(Instant.now().plusSeconds(3600))
                        )))
                .andExpect(status().isOk());

        // Verify user was provisioned
        var user = userRepository.findByOrganizationIdAndExternalSubject(DEFAULT_ORG_ID, subject);
        assertThat(user).isPresent();
        assertThat(user.get().getEmail()).isEqualTo(email);
        assertThat(user.get().getRole()).isEqualTo("Skill_Consumer");
        assertThat(user.get().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("Email validation rejects missing email claim with 403")
    void missingEmailClaimRejectedWith403() throws Exception {
        mockMvc.perform(get("/api/me")
                        .with(jwt().jwt(builder -> builder
                                .subject("auth0|user456")
                                .issuer("https://issuer.example.com")
                                .issuedAt(Instant.now())
                                .expiresAt(Instant.now().plusSeconds(3600))
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Missing or invalid email claim"));
    }

    @Test
    @DisplayName("Email validation rejects invalid email format (no @) with 403")
    void invalidEmailFormatRejectedWith403() throws Exception {
        mockMvc.perform(get("/api/me")
                        .with(jwt().jwt(builder -> builder
                                .subject("auth0|user789")
                                .claim("email", "not-an-email")
                                .issuer("https://issuer.example.com")
                                .issuedAt(Instant.now())
                                .expiresAt(Instant.now().plusSeconds(3600))
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Missing or invalid email claim"));
    }

    @Test
    @DisplayName("Email validation rejects empty email claim with 403")
    void emptyEmailClaimRejectedWith403() throws Exception {
        mockMvc.perform(get("/api/me")
                        .with(jwt().jwt(builder -> builder
                                .subject("auth0|user101")
                                .claim("email", "")
                                .issuer("https://issuer.example.com")
                                .issuedAt(Instant.now())
                                .expiresAt(Instant.now().plusSeconds(3600))
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Missing or invalid email claim"));
    }

    @Test
    @DisplayName("Email validation rejects email with @ at start with 403")
    void emailWithAtStartRejectedWith403() throws Exception {
        mockMvc.perform(get("/api/me")
                        .with(jwt().jwt(builder -> builder
                                .subject("auth0|user202")
                                .claim("email", "@example.com")
                                .issuer("https://issuer.example.com")
                                .issuedAt(Instant.now())
                                .expiresAt(Instant.now().plusSeconds(3600))
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Missing or invalid email claim"));
    }

    /**
     * Test configuration that provides a mock JwtDecoder to avoid
     * requiring a real OIDC provider during integration tests.
     * The spring-security-test jwt() post-processor bypasses the decoder,
     * but we still need a bean to satisfy Spring context startup.
     */
    @TestConfiguration
    static class JwtDecoderTestConfig {
        @Bean
        public JwtDecoder jwtDecoder() {
            // This decoder will reject all raw tokens (for the invalid token test)
            // The jwt() post-processor bypasses this for valid mocked JWTs
            // Must throw BadJwtException so Spring Security returns 401 (not 500)
            return token -> {
                throw new org.springframework.security.oauth2.jwt.BadJwtException("Invalid token");
            };
        }
    }
}
