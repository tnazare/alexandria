package com.alexandria.agent.render;

import com.alexandria.agent.dto.NativeContent;
import com.alexandria.agent.dto.NativeSkillResponse;
import com.alexandria.agent.dto.NativeVariant;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Renders a fetched skill version as Alexandria's structured JSON ({@link NativeSkillResponse}).
 * Body content is sanitized (Req 3.7); identity and governance are always present (Req 6.1). The
 * deprecation warning rides in the {@link com.alexandria.agent.dto.Governance} block.
 */
@Component
public class NativeRenderer implements SkillFetchRenderer {

    public static final String FORMAT = "alexandria_native";

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public FetchResult render(FetchContext ctx) {
        NativeContent content = new NativeContent(
                ContentSanitizer.sanitize(ctx.canonicalInstructions()),
                ContentSanitizer.sanitize(ctx.whenToUse()),
                ContentSanitizer.sanitize(ctx.applicability()),
                ContentSanitizer.sanitize(ctx.limitations()),
                ContentSanitizer.sanitize(ctx.examples()));

        NativeVariant variant = new NativeVariant(
                ctx.selectedTool(),
                ContentSanitizer.sanitize(ctx.selectedInstructions()),
                ctx.isCanonicalFallback());

        NativeSkillResponse body = new NativeSkillResponse(
                ctx.ref(), ctx.governance(), ctx.resolution(), content, variant);

        return new FetchResult(FORMAT, MediaType.APPLICATION_JSON_VALUE, body);
    }
}
