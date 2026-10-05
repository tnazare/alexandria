package com.alexandria.agent.search;

import com.alexandria.config.SearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Selects the active {@link SearchStrategy} from {@code alexandria.search.mode}. The lookup happens
 * once per resolve call (cheap map get), and the chosen strategy is the single branch point for
 * retrieval — downstream resolve logic is identical across modes.
 *
 * <p>{@code SearchProperties} already validates that {@code mode} is one of {@code fts | semantic |
 * hybrid} at startup, so an unknown mode never reaches here. Modes with no registered strategy yet
 * ({@code semantic}, {@code hybrid} in this task) fail fast with a clear "not yet enabled" message
 * rather than silently falling back to FTS, so a misconfigured run is never mislabeled.</p>
 */
@Component
public class SearchStrategySelector {

    private static final Logger log = LoggerFactory.getLogger(SearchStrategySelector.class);

    private final SearchProperties searchProperties;
    private final Map<String, SearchStrategy> strategiesByMode;

    public SearchStrategySelector(SearchProperties searchProperties, List<SearchStrategy> strategies) {
        this.searchProperties = searchProperties;
        this.strategiesByMode = strategies.stream()
                .collect(Collectors.toUnmodifiableMap(SearchStrategy::mode, Function.identity()));
        log.info("Skill resolver search mode: {} (registered strategies: {})",
                searchProperties.mode(), this.strategiesByMode.keySet());
    }

    /**
     * Returns the strategy for the configured mode.
     *
     * @throws SearchModeNotEnabledException when the configured mode is valid but has no strategy
     *                                       wired yet (e.g. {@code semantic}/{@code hybrid} before
     *                                       their enabling tasks land).
     */
    public SearchStrategy active() {
        String mode = searchProperties.mode();
        SearchStrategy strategy = strategiesByMode.get(mode);
        if (strategy == null) {
            throw new SearchModeNotEnabledException(mode);
        }
        return strategy;
    }
}
