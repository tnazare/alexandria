package com.alexandria.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Integration tests for {@link SemanticSearchStartupValidator} against a real pgvector-enabled
 * PostgreSQL (Testcontainers, {@code pgvector/pgvector:pg16}) with the full migration chain applied.
 *
 * <p>Covers the fail-fast guarantees: {@code fts} mode is a no-op, {@code semantic}/{@code hybrid}
 * mode passes when the extension is present and the configured dimension matches the column, and a
 * dimension mismatch throws a clear error at startup.</p>
 *
 * <p><b>Validates: Requirements R2.5, R3.5</b></p>
 */
@DisplayName("SemanticSearchStartupValidator (pgvector)")
class SemanticSearchStartupValidatorIntegrationTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("alexandria_validator_test")
                    .withUsername("test")
                    .withPassword("test");

    private static javax.sql.DataSource dataSource;

    @BeforeAll
    static void startAndMigrate() {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        dataSource = DataSourceBuilder.create()
                .url(POSTGRES.getJdbcUrl())
                .username(POSTGRES.getUsername())
                .password(POSTGRES.getPassword())
                .driverClassName("org.postgresql.Driver")
                .build();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private SemanticSearchStartupValidator validatorFor(String mode, int dim) {
        return new SemanticSearchStartupValidator(
                new SearchProperties(mode, dim, null, null), dataSource);
    }

    @Test
    @DisplayName("fts mode is a no-op (does not touch the database)")
    void ftsModeIsNoOp() {
        // Even with a deliberately wrong dim, fts never validates — existing deployments unaffected.
        assertThatCode(() -> validatorFor("fts", 999).run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("semantic mode passes when extension present and dimension matches column")
    void semanticModePassesWhenValid() {
        assertThatCode(() -> validatorFor("semantic", 256).run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("hybrid mode passes when extension present and dimension matches column")
    void hybridModePassesWhenValid() {
        assertThatCode(() -> validatorFor("hybrid", 256).run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("dimension mismatch fails fast with a clear message (R3.5)")
    void dimensionMismatchFailsFast() {
        assertThatThrownBy(() -> validatorFor("semantic", 512).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Embedding dimension mismatch")
                .hasMessageContaining("512")
                .hasMessageContaining("256");
    }
}
