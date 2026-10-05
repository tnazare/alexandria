package com.alexandria.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Logs the active retrieval mode at startup so a deployment (and the benchmark harness) can attribute
 * a run to the correct search mode (R1.4). Validation of the mode itself happens earlier, in
 * {@link SearchProperties}'s compact constructor (fail-fast on unknown mode, R1.3).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchModeStartupLogger {

    private final SearchProperties searchProperties;

    @PostConstruct
    void logActiveSearchMode() {
        log.info("Alexandria search mode: {} (embeddingProvider={}, embeddingDim={})",
                searchProperties.mode(),
                searchProperties.embeddingProvider(),
                searchProperties.embeddingDim());
    }
}
