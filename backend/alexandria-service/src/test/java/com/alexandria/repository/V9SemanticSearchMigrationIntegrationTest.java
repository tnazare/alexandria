package com.alexandria.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Integration test for the V9 semantic-search migration against a real pgvector-enabled PostgreSQL
 * (Testcontainers, {@code pgvector/pgvector:pg16}).
 *
 * <p>Drives Flyway directly (independent of the Spring context) so it can assert both that the full
 * migration chain through V9 applies cleanly on an empty database and that it is idempotent — a
 * second {@code migrate()} is a no-op and the extension / table / indexes created by V9 are present
 * and correctly shaped.</p>
 *
 * <p><b>Validates: Requirements R2.1, R2.2, R2.3, R2.4</b></p>
 */
@DisplayName("V9 Semantic Search Migration (pgvector)")
class V9SemanticSearchMigrationIntegrationTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("alexandria_migration_test")
                    .withUsername("test")
                    .withPassword("test");

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false) // test-only: allow clean() to reset the throwaway container
                .load();
    }

    @Test
    @DisplayName("full migration chain through V9 applies cleanly on an empty database")
    void migrationChainAppliesCleanly() {
        Flyway flyway = flyway();
        flyway.clean(); // start from a clean schema (cleanDisabled is false by default here)
        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrations)
                .as("V9 is included in the applied chain")
                .anyMatch(m -> "9".equals(m.version));
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("9");
    }

    @Test
    @DisplayName("V9 creates the vector extension, skill_embeddings table, and expected indexes")
    void v9CreatesExpectedObjects() throws Exception {
        Flyway flyway = flyway();
        flyway.clean();
        flyway.migrate();

        try (Connection conn = POSTGRES.createConnection("")) {
            assertThat(scalarExists(conn,
                    "SELECT 1 FROM pg_extension WHERE extname = 'vector'"))
                    .as("vector extension installed").isTrue();

            assertThat(scalarExists(conn,
                    "SELECT 1 FROM information_schema.tables "
                            + "WHERE table_name = 'skill_embeddings'"))
                    .as("skill_embeddings table exists").isTrue();

            // embedding column is vector(256) — atttypmod carries the pgvector dimension directly.
            int dim = scalarInt(conn,
                    "SELECT atttypmod FROM pg_attribute "
                            + "WHERE attrelid = 'skill_embeddings'::regclass "
                            + "AND attname = 'embedding' AND NOT attisdropped");
            assertThat(dim).as("embedding dimension").isEqualTo(256);

            assertThat(scalarExists(conn,
                    "SELECT 1 FROM pg_indexes WHERE indexname = 'idx_skill_embeddings_hnsw'"))
                    .as("HNSW index exists").isTrue();
            assertThat(scalarExists(conn,
                    "SELECT 1 FROM pg_indexes WHERE indexname = 'idx_skill_embeddings_org'"))
                    .as("org index exists").isTrue();
        }
    }

    @Test
    @DisplayName("migration is idempotent — a second migrate applies nothing and schema is unchanged")
    void migrationIsIdempotent() throws Exception {
        Flyway flyway = flyway();
        flyway.clean();
        flyway.migrate();

        // A second migrate on an already-migrated database must apply zero new migrations.
        MigrateResult second = flyway.migrate();
        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted)
                .as("second migrate is a no-op").isZero();

        // The V9 objects are still present and intact after the repeated run.
        try (Connection conn = POSTGRES.createConnection("")) {
            assertThat(scalarExists(conn,
                    "SELECT 1 FROM pg_extension WHERE extname = 'vector'")).isTrue();
            assertThat(scalarExists(conn,
                    "SELECT 1 FROM information_schema.tables "
                            + "WHERE table_name = 'skill_embeddings'")).isTrue();
        }
    }

    private static boolean scalarExists(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next();
        }
    }

    private static int scalarInt(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }
}
