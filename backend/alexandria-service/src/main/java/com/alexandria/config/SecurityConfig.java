package com.alexandria.config;

import com.alexandria.audit.AuditEventRepository;
import com.alexandria.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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
@EnableConfigurationProperties({OidcProperties.class, OrganizationProperties.class})
@RequiredArgsConstructor
public class SecurityConfig {

    private final OidcProperties oidcProperties;
    private final OrganizationProperties organizationProperties;
    private final UserRepository userRepository;
    private final AuditEventRepository auditEventRepository;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                )
                .addFilterAfter(userProvisioningFilter(), BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public UserProvisioningFilter userProvisioningFilter() {
        return new UserProvisioningFilter(userRepository, auditEventRepository, organizationProperties);
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        return new JwtAuthenticationConverter();
    }
}
