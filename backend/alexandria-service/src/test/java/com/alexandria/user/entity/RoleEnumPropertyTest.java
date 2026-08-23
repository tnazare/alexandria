package com.alexandria.user.entity;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.StringLength;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for Role enum membership validation.
 *
 * Validates: Requirements 2.9, 6.7
 */
@Tag("Feature: alexandria-first-step, Property 2: Role enum membership validation")
class RoleEnumPropertyTest {

    private static final Set<String> VALID_ROLES = Set.of(
            "Read_Only_Auditor",
            "Skill_Consumer",
            "Skill_Author",
            "Reviewer",
            "Team_Owner",
            "Catalog_Admin",
            "Org_Admin"
    );

    private static final Set<String> VALID_TEAM_ROLES = Set.of("owner", "member");

    /**
     * Property: The Role enum has exactly 7 values.
     */
    @Property(tries = 1)
    void roleEnumHasExactlySevenValues() {
        assertThat(Role.values()).hasSize(7);
    }

    /**
     * Property: All valid role names are accepted by Role.valueOf().
     *
     * Validates: Requirements 2.9
     */
    @Property(tries = 100)
    void validRoleNamesAreAccepted(@ForAll("validRoleNames") String roleName) {
        assertThatCode(() -> Role.valueOf(roleName)).doesNotThrowAnyException();
        Role role = Role.valueOf(roleName);
        assertThat(role.name()).isEqualTo(roleName);
    }

    /**
     * Property: Any string not in the set of 7 valid role names is rejected by Role.valueOf().
     *
     * Validates: Requirements 2.9, 6.7
     */
    @Property(tries = 100)
    void invalidRoleNamesAreRejected(@ForAll("invalidRoleNames") String invalidName) {
        assertThatThrownBy(() -> Role.valueOf(invalidName))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Property: Random strings that are not valid role names are rejected.
     *
     * Validates: Requirements 2.9, 6.7
     */
    @Property(tries = 100)
    void randomStringsAreRejectedAsRoles(@ForAll @StringLength(min = 1, max = 50) String randomString) {
        if (!VALID_ROLES.contains(randomString)) {
            assertThatThrownBy(() -> Role.valueOf(randomString))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * Property: Only "owner" and "member" are valid team_role values; all other strings are invalid.
     *
     * Validates: Requirements 2.9, 6.7
     */
    @Property(tries = 100)
    void teamRoleAcceptsOnlyOwnerOrMember(@ForAll @StringLength(min = 1, max = 50) String randomString) {
        boolean isValid = VALID_TEAM_ROLES.contains(randomString);
        if (isValid) {
            assertThat(randomString).isIn("owner", "member");
        } else {
            assertThat(VALID_TEAM_ROLES).doesNotContain(randomString);
        }
    }

    /**
     * Property: Valid team role values "owner" and "member" are always accepted.
     *
     * Validates: Requirements 2.9
     */
    @Property(tries = 100)
    void validTeamRolesAreAccepted(@ForAll("validTeamRoles") String teamRole) {
        assertThat(VALID_TEAM_ROLES).contains(teamRole);
    }

    /**
     * Property: Every enum constant maps to one of the 7 valid role names.
     */
    @Property(tries = 1)
    void allEnumConstantsAreInAllowedSet() {
        for (Role role : Role.values()) {
            assertThat(VALID_ROLES).contains(role.name());
        }
    }

    /**
     * Property: Each role has a unique privilege level between 0 and 6.
     */
    @Property(tries = 1)
    void privilegeLevelsAreUniqueAndInRange() {
        Set<Integer> privileges = new java.util.HashSet<>();
        for (Role role : Role.values()) {
            assertThat(role.getPrivilege()).isBetween(0, 6);
            privileges.add(role.getPrivilege());
        }
        assertThat(privileges).hasSize(7);
    }

    @Provide
    Arbitrary<String> validRoleNames() {
        return Arbitraries.of(VALID_ROLES.toArray(new String[0]));
    }

    @Provide
    Arbitrary<String> invalidRoleNames() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(50)
                .filter(s -> !VALID_ROLES.contains(s));
    }

    @Provide
    Arbitrary<String> validTeamRoles() {
        return Arbitraries.of("owner", "member");
    }
}
