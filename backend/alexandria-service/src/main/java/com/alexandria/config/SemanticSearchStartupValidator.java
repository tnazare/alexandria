package com.alexandria.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Fail-fast validation of the pgvector prerequisites for semantic retrieval.
 *
 * <p>When {@code alexandria.search.mode} is {@code semantic} or {@code hybrid}, the system cannot
 * return correct results unless (a) the pgvector {@code vector} extension is installed and (b) the
 * configured {@link SearchProperties#embeddingDim()} matches the dimension of the
 * {@code skill_embeddings.embedding} column. Either problem would otherwise surface as wrong
 * distances or empty results rather than an obvious error. This validator turns both into a loud,
 * early startup failure with an actionable message (R2.5, R3.5).</p>
 *
 * <p>When the mode is {@code fts} (the default), this validator is a no-op: existing deployments that
 * never provisioned pgvector are entirely unaffected (R6).</p>
 *
 * <p>Runs as an {@link ApplicationRunner} so it executes after Flyway has applied migrations (the
 * extension and column are created by {@code V9__semantic_search.sql}).</p>
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class SemanticSearchStartupValidator implements ApplicationRunner {

    private final SearchProperties searchProperties;
    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String mode = searchProperties.mode();
        if (!"semantic".equals(mode) && !"hybrid".equals(mode)) {
            // fts (default): semantic storage is not required; stay a no-op.
            return;
        }

        try (Connection conn = dataSource.getConnection()) {
            assertVectorExtensionPresent(conn, mode);
            assertEmbeddingDimensionMatches(conn);
        }

        log.info("Semantic search prerequisites validated: pgvector present, embedding dimension {} "
                + "matches DB column (mode={}).", searchProperties.embeddingDim(), mode);
    }

    private void assertVectorExtensionPresent(Connection conn, String mode) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM pg_extension WHERE extname = 'vector'")) {
            if (!rs.next()) {
                throw new IllegalStateException(
                        "alexandria.search.mode=" + mode + " requires the pgvector 'vector' extension, "
                                + "but it is not installed in this database. Deploy on a pgvector-enabled "
                                + "Postgres image (e.g. pgvector/pgvector:pg16) so migration V9 can run "
                                + "'CREATE EXTENSION vector', or set ALEXANDRIA_SEARCH_MODE=fts.");
            }
        }
    }

    private void assertEmbeddingDimensionMatches(Connection conn) throws SQLException {
        int configuredDim = searchProperties.embeddingDim();
        // For a pgvector column, pg_attribute.atttypmod holds the declared dimension directly
        // (unlike varchar, there is no length-header offset).
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT atttypmod FROM pg_attribute "
                             + "WHERE attrelid = 'skill_embeddings'::regclass "
                             + "AND attname = 'embedding' AND NOT attisdropped")) {
            if (!rs.next()) {
                throw new IllegalStateException(
                        "Semantic search is enabled but the skill_embeddings.embedding column was not "
                                + "found. Ensure migration V9__semantic_search.sql has been applied.");
            }
            int columnDim = rs.getInt("atttypmod");
            if (columnDim != configuredDim) {
                throw new IllegalStateException(
                        "Embedding dimension mismatch: ALEXANDRIA_EMBEDDING_DIM=" + configuredDim
                                + " but skill_embeddings.embedding is vector(" + columnDim + "). "
                                + "The configured dimension MUST equal the DB column dimension. Align the "
                                + "env var and the migration before enabling semantic/hybrid search.");
            }
        }
    }
}
