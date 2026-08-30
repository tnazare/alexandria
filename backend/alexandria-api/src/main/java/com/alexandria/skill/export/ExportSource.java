package com.alexandria.skill.export;

/**
 * How a rendered artifact is consumed by the client, recorded on the resulting usage event.
 *
 * <p>{@code COPY} maps to a usage event of type {@code copy}; {@code DOWNLOAD} maps to
 * {@code export}. Defaults to {@code DOWNLOAD} when the {@code source} parameter is absent.</p>
 */
public enum ExportSource {

    COPY("copy"),
    DOWNLOAD("download");

    private final String wireValue;

    ExportSource(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    /**
     * Resolves an {@link ExportSource} from its wire value, case-insensitively, defaulting to
     * {@link #DOWNLOAD} when the value is null or blank.
     *
     * @param value the {@code source} query parameter
     * @return the matching source, or {@code DOWNLOAD} when absent
     * @throws IllegalArgumentException if a non-blank value does not match a known source
     */
    public static ExportSource fromWire(String value) {
        if (value == null || value.isBlank()) {
            return DOWNLOAD;
        }
        for (ExportSource source : values()) {
            if (source.wireValue.equalsIgnoreCase(value.trim())) {
                return source;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported export source: '" + value + "'. Supported sources: copy, download");
    }
}
