package com.alexandria.skill.lifecycle;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link SkillLifecycleTransition}.
 *
 * <p><b>Property 1: Transition legality is total and matches the table.</b> For every
 * (status, action) pair, {@code isAllowed} returns exactly the value in the design legality
 * table, and no pair is undefined.</p>
 *
 * <p><b>Validates: Requirements 1.3, 2.2, 3.2, 4.2, 5.4</b></p>
 */
@Tag("skill-lifecycle")
@Tag("Property-1-Transition-legality")
class SkillLifecycleTransitionPropertyTest {

    private static final Set<String> ALL_STATUSES = Set.of(
            "Draft", "Published", "Team_Approved", "Company_Approved",
            "Deprecated", "Archived", "Blocked");

    /** Independent re-derivation of the legality table (not the implementation under test). */
    private static boolean expected(String status, LifecycleAction action) {
        boolean active = Set.of("Draft", "Published", "Team_Approved", "Company_Approved").contains(status);
        return switch (action) {
            case DEPRECATE -> active;
            case ARCHIVE -> active || "Deprecated".equals(status);
            case BLOCK -> !"Blocked".equals(status);
            case RESTORE -> Set.of("Deprecated", "Archived", "Blocked").contains(status);
        };
    }

    @Property(tries = 200)
    void isAllowedMatchesTheTable(@ForAll("statusActionPairs") StatusAction pair) {
        assertThat(SkillLifecycleTransition.isAllowed(pair.status(), pair.action()))
                .as("isAllowed(%s, %s)", pair.status(), pair.action())
                .isEqualTo(expected(pair.status(), pair.action()));
    }

    @Property(tries = 50)
    void nullInputsAreNeverAllowed(@ForAll("actions") LifecycleAction action) {
        assertThat(SkillLifecycleTransition.isAllowed(null, action)).isFalse();
        assertThat(SkillLifecycleTransition.isAllowed("Draft", null)).isFalse();
    }

    @Property(tries = 50)
    void targetStatusIsFixedForNonRestoreActions(@ForAll("nonRestoreActions") LifecycleAction action) {
        String target = SkillLifecycleTransition.targetStatus(action);
        assertThat(ALL_STATUSES).contains(target);
        assertThat(SkillLifecycleTransition.isGoverned(target)).isTrue();
    }

    record StatusAction(String status, LifecycleAction action) {}

    @Provide
    Arbitrary<StatusAction> statusActionPairs() {
        Arbitrary<String> statuses = Arbitraries.of(ALL_STATUSES.toArray(new String[0]));
        Arbitrary<LifecycleAction> actions = Arbitraries.of(LifecycleAction.values());
        return Combinators.combine(statuses, actions).as(StatusAction::new);
    }

    @Provide
    Arbitrary<LifecycleAction> actions() {
        return Arbitraries.of(LifecycleAction.values());
    }

    @Provide
    Arbitrary<LifecycleAction> nonRestoreActions() {
        return Arbitraries.of(LifecycleAction.DEPRECATE, LifecycleAction.BLOCK, LifecycleAction.ARCHIVE);
    }
}
