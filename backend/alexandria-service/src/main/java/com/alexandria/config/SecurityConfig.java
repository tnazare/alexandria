package com.alexandria.config;

import com.alexandria.audit.AuditEventRepository;
import com.alexandria.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration. Stateless with OIDC/JWT authentication.
 *
 * <p>Configures the application as an OAuth2 resource server that validates
 * JWT access tokens issued by the configured OIDC identity provider.
 * Health and info actuator endpoints are publicly accessible; all other
 * endpoints require a valid JWT bearer token.</p>
 *
 * <p>After JWT authentication, the {@link UserProvisioningFilter} runs to
 * look up or auto-provision the user and enrich the SecurityContext with
 * Alexandria-specific user details.</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({OidcProperties.class, OrganizationProperties.class, ResolverProperties.class, SearchProperties.class})
@RequiredArgsConstructor
public class SecurityConfig {

    private final OidcProperties oidcProperties;
    private final OrganizationProperties organizationProperties;
    private final UserRepository userRepository;
    private final AuditEventRepository auditEventRepository;
    private final com.alexandria.agent.ServiceTokenRepository serviceTokenRepository;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        // Ignore Alexandria service tokens here so the JWT decoder never sees them;
                        // they are handled by ServiceTokenAuthFilter. Other bearer tokens resolve
                        // normally as JWTs.
                        .bearerTokenResolver(serviceTokenAwareBearerResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                )
                // Service-token auth runs before the JWT bearer filter so a non-JWT agent token is
                // recognized and short-circuited before the JWT decoder would reject it. Non-service
                // tokens pass through untouched, preserving the existing OIDC flow.
                .addFilterBefore(serviceTokenAuthFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(userProvisioningFilter(), BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        var configuration = new org.springframework.web.cors.CorsConfiguration();
        configuration.setAllowedOriginPatterns(java.util.List.of("http://localhost:*", "http://127.0.0.1:*"));
        configuration.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(java.util.List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public UserProvisioningFilter userProvisioningFilter() {
        return new UserProvisioningFilter(userRepository, auditEventRepository, organizationProperties);
    }

    @Bean
    public ServiceTokenAuthFilter serviceTokenAuthFilter() {
        return new ServiceTokenAuthFilter(serviceTokenRepository);
    }

    /**
     * Bearer-token resolver that hides Alexandria service tokens ({@code alx_} prefix) from the JWT
     * resource server, so the JWT decoder is never invoked on them. All other bearer tokens are
     * resolved by the default resolver and validated as JWTs as before.
     */
    @Bean
    public org.springframework.security.oauth2.server.resource.web.BearerTokenResolver serviceTokenAwareBearerResolver() {
        var delegate = new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver();
        return request -> {
            String token = delegate.resolve(request);
            if (token != null && token.startsWith(ServiceTokenAuthFilter.TOKEN_PREFIX)) {
                return null;
            }
            return token;
        };
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        return new JwtAuthenticationConverter();
    }

    /**
     * Custom JwtDecoder that uses JWK Set URI directly without issuer validation.
     * This is necessary for local development with the mock-oauth2-server where
     * the issuer in the token (http://localhost:9000/...) differs from the URL
     * the backend uses to reach the server (http://heptastadion:9000/...).
     *
     * <p>In production, set spring.security.oauth2.resourceserver.jwt.issuer-uri
     * and remove jwk-set-uri to get full issuer validation.</p>
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            return NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                    .build();
        }
        // Fall back to issuer-uri based auto-configuration by returning
        // a decoder built from the OIDC issuer URI
        return NimbusJwtDecoder.withIssuerLocation(oidcProperties.issuerUri())
                .build();
    }
}
