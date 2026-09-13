package com.alexandria.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Shared hashing and generation helper for Alexandria service tokens.
 *
 * <p>Both {@link ServiceTokenAuthFilter} (which looks a presented token up by hash) and the
 * service-token minting service (which stores the hash of a freshly generated token) use this
 * single implementation, so a minted token always hashes to exactly what the filter looks up and
 * the two can never drift.</p>
 */
public final class ServiceTokenHasher {

    /** Non-secret prefix identifying an Alexandria service token. */
    public static final String TOKEN_PREFIX = "alx_";

    /** Length of the non-secret display fragment retained for listings. */
    public static final int PREFIX_DISPLAY_LENGTH = 12;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 32;

    private ServiceTokenHasher() {
    }

    /**
     * Returns the lowercase SHA-256 hex digest of the given value (UTF-8). This is the canonical
     * storage form for a service token's secret.
     */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Generates a new cryptographically strong service-token secret with the {@code alx_} prefix and
     * URL-safe, unpadded Base64 body.
     */
    public static String generateSecret() {
        byte[] raw = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(raw);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * Returns the non-secret display prefix of a token secret (at most
     * {@link #PREFIX_DISPLAY_LENGTH} characters), safe to persist and show in listings.
     */
    public static String displayPrefix(String secret) {
        return secret.substring(0, Math.min(PREFIX_DISPLAY_LENGTH, secret.length()));
    }
}
