package com.alexandria.skill;

import com.alexandria.common.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Property-based tests verifying that Jakarta Bean Validation rejects invalid
 * {@code CreateSkillRequest} payloads at the controller layer before the request
 * reaches the service.
 *
 * <p><b>Property 4: Input validation rejects invalid payloads</b> —
 * for any request that violates a field constraint (name length 3–200,
 * description length 1–2000, canonicalInstructions length 1–100000, required
 * fields present), the controller returns HTTP 400 and never invokes the
 * service, leaving skill state unchanged.</p>
 *
 * <p>Uses a standalone MockMvc so that {@code @Valid} on the controller method
 * argument triggers bean validation and the shared {@link GlobalExceptionHandler}
 * maps the resulting {@code MethodArgumentNotValidException} to 400.</p>
 *
 * <p><b>Validates: Requirements 1.8, 3.10</b></p>
 */
@Tag("skill-crud")
@Tag("Property-4-Input-validation")
class SkillInputValidationPropertyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Property: any invalid create-skill payload yields HTTP 400 and the service is never called.
     */
    @Property(tries = 100)
    void invalidCreateSkillPayloadReturns400AndSkipsService(
            @ForAll("invalidCreateSkillJson") String requestJson) throws Exception {

        SkillService skillService = mock(SkillService.class);
        SkillResponseMapper responseMapper = mock(SkillResponseMapper.class);
        SkillResource controller = new SkillResource(skillService, responseMapper);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        // Skill state unchanged — the service was never invoked.
        verifyNoInteractions(skillService);
        verifyNoInteractions(responseMapper);
    }

    // --- Providers ---

    /**
     * Generates JSON bodies for CreateSkillRequest that each violate at least one constraint.
     */
    @Provide
    Arbitrary<String> invalidCreateSkillJson() {
        return Arbitraries.oneOf(
                nameTooShort(),
                nameTooLong(),
                blankName(),
                blankDescription(),
                descriptionTooLong(),
                missingOwnerTeam(),
                blankInstructions()
        );
    }

    private Arbitrary<String> nameTooShort() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(2)
                .map(name -> json(name, "valid description", UUID.randomUUID().toString(), "valid instructions"));
    }

    private Arbitrary<String> nameTooLong() {
        return Arbitraries.strings().alpha().ofMinLength(201).ofMaxLength(250)
                .map(name -> json(name, "valid description", UUID.randomUUID().toString(), "valid instructions"));
    }

    private Arbitrary<String> blankName() {
        return Arbitraries.of("", "   ")
                .map(name -> json(name, "valid description", UUID.randomUUID().toString(), "valid instructions"));
    }

    private Arbitrary<String> blankDescription() {
        return Arbitraries.of("", "   ")
                .map(desc -> json("Valid Name", desc, UUID.randomUUID().toString(), "valid instructions"));
    }

    private Arbitrary<String> descriptionTooLong() {
        return Arbitraries.strings().alpha().ofMinLength(2001).ofMaxLength(2100)
                .map(desc -> json("Valid Name", desc, UUID.randomUUID().toString(), "valid instructions"));
    }

    private Arbitrary<String> missingOwnerTeam() {
        // ownerTeamId omitted entirely -> @NotNull violation
        return Arbitraries.just("""
                {"name":"Valid Name","description":"valid description","canonicalInstructions":"valid instructions"}""");
    }

    private Arbitrary<String> blankInstructions() {
        return Arbitraries.of("", "   ")
                .map(instr -> json("Valid Name", "valid description", UUID.randomUUID().toString(), instr));
    }

    private static String json(String name, String description, String ownerTeamId, String instructions) {
        return """
                {"name":%s,"description":%s,"ownerTeamId":%s,"canonicalInstructions":%s}"""
                .formatted(quote(name), quote(description), quote(ownerTeamId), quote(instructions));
    }

    private static String quote(String value) {
        // Minimal JSON string escaping for generated alpha strings and fixed values.
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
