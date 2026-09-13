package com.alexandria.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ServiceTokenHasher} (mcp-client-readiness Requirement 6.2 — hashing parity).
 * Guarantees a minted token hashes to exactly what {@code ServiceTokenAuthFilter} looks up.
 */
@Tag("mcp-client-readiness")
class ServiceTokenHasherTest {

    @Test
    void sha256HexMatchesKnownVector() {
        // Known SHA-256("abc") hex vector.
        assertThat(ServiceTokenHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sha256HexIsStableAndDeterministic() {
        String secret = ServiceTokenHasher.generateSecret();
        assertThat(ServiceTokenHasher.sha256Hex(secret))
                .isEqualTo(ServiceTokenHasher.sha256Hex(secret));
    }

    @Test
    void generatedSecretHasAlxPrefixAndDisplayPrefix() {
        String secret = ServiceTokenHasher.generateSecret();

        assertThat(secret).startsWith(ServiceTokenHasher.TOKEN_PREFIX);
        assertThat(ServiceTokenHasher.displayPrefix(secret))
                .hasSize(ServiceTokenHasher.PREFIX_DISPLAY_LENGTH)
                .isEqualTo(secret.substring(0, ServiceTokenHasher.PREFIX_DISPLAY_LENGTH));
    }

    @Test
    void distinctSecretsHashDifferently() {
        assertThat(ServiceTokenHasher.sha256Hex(ServiceTokenHasher.generateSecret()))
                .isNotEqualTo(ServiceTokenHasher.sha256Hex(ServiceTokenHasher.generateSecret()));
    }
}
