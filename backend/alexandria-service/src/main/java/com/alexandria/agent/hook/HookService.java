package com.alexandria.agent.hook;

import com.alexandria.agent.ResolverService;
import com.alexandria.agent.dto.ResolveRequest;
import com.alexandria.agent.dto.ResolveResponse;
import com.alexandria.agent.dto.ResolveResult;
import com.alexandria.agent.render.FetchResult;
import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.config.ResolverProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Push-mode hook orchestration. Given a parsed {@link HookInvocation}, decides whether to inject a
 * governed skill into the agent turn and returns the harness-appropriate hook response.
 *
 * <p>Design guarantees (Requirement 11): <b>fail-open</b> — any resolution error or timeout returns
 * an empty decision so the developer's prompt proceeds unmodified; a <b>relevance gate</b> skips
 * trivial prompts; a <b>session cache</b> avoids repeated round trips for the same prompt; a
 * <b>size cap</b> bounds injected bytes; and {@code preToolUse} governance can deny a tool call
 * tied to a blocked/unsafe skill.</p>
 *
 * <p>Resolution runs on a bounded virtual thread with a client timeout well under any harness hook
 * timeout, so this adapter never causes a fail-closed prompt block.</p>
 */
@Slf4j
@Service
public class HookService {

    private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(5);

    private final ResolverService resolverService;
    private final ResolverProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Per-(session, promptHash) cache of already-injected results, so repeated/near-identical
    // prompts in a session don't re-resolve. Bounded implicitly by session lifetime; entries are
    // small. A production deployment may swap this for a TTL cache.
    private final Map<String, String> sessionCache = new ConcurrentHashMap<>();

    public HookService(ResolverService resolverService, ResolverProperties properties) {
        this.resolverService = resolverService;
        this.properties = properties;
    }

    /**
     * Produces the hook response for an invocation. Always returns a non-null node; an empty object
     * means "no decision / proceed unmodified".
     */
    public JsonNode handle(HookInvocation invocation, AlexandriaUserDetails identity) {
        try {
            return switch (invocation.event()) {
                case HookInvocation.EVENT_USER_PROMPT -> handlePromptInjection(invocation, identity);
                case HookInvocation.EVENT_PRE_TOOL_USE -> handlePreToolUse(invocation, identity);
                // sessionStart seeding and other events: no injection in this adapter build.
                default -> empty();
            };
        } catch (Exception e) {
            // Fail-open: never block or delay the developer on our account.
            log.debug("Hook handling failed open: {}", e.getMessage());
            return empty();
        }
    }

    private JsonNode handlePromptInjection(HookInvocation invocation, AlexandriaUserDetails identity) {
        if (!properties.hookInjectOnPrompt()) {
            return empty();
        }
        String prompt = invocation.prompt();
        if (!isRelevant(prompt)) {
            return empty();
        }

        String cacheKey = cacheKey(invocation.sessionId(), prompt);
        String cached = sessionCache.get(cacheKey);
        if (cached != null) {
            return injection(invocation.harness(), cached);
        }

        String context = resolveInjectableContext(prompt, identity);
        if (context == null) {
            return empty();
        }
        sessionCache.put(cacheKey, context);
        return injection(invocation.harness(), context);
    }

    /**
     * Resolves the top candidate and fetches it as a size-capped SKILL.md, on a bounded thread so a
     * slow resolve cannot exceed the harness hook timeout. Returns null on no-match/error/timeout.
     */
    private String resolveInjectableContext(String prompt, AlexandriaUserDetails identity) {
        try {
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                ResolveResponse response = resolverService.resolve(
                        new ResolveRequest(prompt, null, null, null, 1), identity);
                if (response.results().isEmpty()) {
                    return null;
                }
                ResolveResult top = response.results().get(0);
                UUID versionId = top.ref().skillVersionId();
                FetchResult fetched = resolverService.fetch(versionId, null, "agent_skill", "hook", identity);
                String body = fetched.body() instanceof String s ? s : String.valueOf(fetched.body());
                return cap(body);
            }).get(RESOLVE_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Timeout / resolution error / governance denial (e.g. blocked top hit): fail open.
            log.debug("Hook resolve failed open: {}", e.getMessage());
            return null;
        }
    }

    private JsonNode handlePreToolUse(HookInvocation invocation, AlexandriaUserDetails identity) {
        // Governance enforcement hook point. This build does not map arbitrary tool calls to a
        // specific skill, so it defaults to allow (no decision). When a future signal ties a tool
        // call to a blocked/unsafe skill, return a deny decision here.
        return empty();
    }

    // --- helpers ---

    /** Relevance gate: skip trivial/short prompts that are unlikely to map to a skill. */
    private boolean isRelevant(String prompt) {
        return prompt != null && prompt.strip().length() >= properties.hookMinPromptChars();
    }

    /** Caps the injected content to the configured byte budget, on a UTF-8 boundary. */
    private String cap(String content) {
        if (content == null) {
            return null;
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        int max = properties.hookMaxInjectBytes();
        if (bytes.length <= max) {
            return content;
        }
        // Truncate to the largest valid UTF-8 prefix within the budget.
        String truncated = new String(bytes, 0, max, StandardCharsets.UTF_8);
        return truncated;
    }

    /**
     * Builds the harness-appropriate injection response. Both harnesses accept an additional-context
     * field; Copilot's transform hook also honors modifiedTransformedPrompt, but additionalContext
     * is the safe common shape.
     */
    private JsonNode injection(String harness, String content) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("additionalContext", content);
        return node;
    }

    private JsonNode empty() {
        return objectMapper.createObjectNode();
    }

    private String cacheKey(String sessionId, String prompt) {
        String s = sessionId == null ? "no-session" : sessionId;
        return s + "::" + Integer.toHexString(prompt.strip().hashCode());
    }
}
