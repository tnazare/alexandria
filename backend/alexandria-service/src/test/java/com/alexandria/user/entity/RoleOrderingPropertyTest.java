package com.alexandria.user.entity;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for role privilege ordering consistency.
 *
 * Validates: Requirements 6.2
 */
@Tag("Feature: alexandria-first-step, Property 9: Role privilege ordering consistency")
class RoleOrderingPropertyTest {

    /**
     * Property: For any two roles A and B where A.privilege < B.privilege,
     * B.hasAtLeast(A) must be true — the higher-privileged role can do
     * everything the lower-privileged role can.
     *
     * Validates: Requirements 6.2
     */
    @Property(tries = 100)
    void higherPrivilegeRoleHasAtLeastLowerPrivilegeRole(
            @ForAll("roles") Role roleA,
            @ForAll("roles") Role roleB) {
        if (roleA.getPrivilege() < roleB.getPrivilege()) {
            assertThat(roleB.hasAtLeast(roleA))
                    .as("Role %s (privilege %d) should have at least the access of %s (privilege %d)",
                            roleB, roleB.getPrivilege(), roleA, roleA.getPrivilege())
                    .isTrue();
        }
    }

    /**
     * Property: Ordering is strict — if A != B and A.privilege < B.privilege,
     * then A does NOT have at least B's privilege.
     *
     * Validates: Requirements 6.2
     */
    @Property(tries = 100)
    void lowerPrivilegeRoleDoesNotHaveAtLeastHigherPrivilegeRole(
            @ForAll("roles") Role roleA,
            @ForAll("roles") Role roleB) {
        if (roleA != roleB && roleA.getPrivilege() < roleB.getPrivilege()) {
            assertThat(roleA.hasAtLeast(roleB))
                    .as("Role %s (privilege %d) should NOT have at least the access of %s (privilege %d)",
                            roleA, roleA.getPrivilege(), roleB, roleB.getPrivilege())
                    .isFalse();
        }
    }

    /**
     * Property: Transitivity — if A.hasAtLeast(B) and B.hasAtLeast(C),
     * then A.hasAtLeast(C).
     *
     * Validates: Requirements 6.2
     */
    @Property(tries = 100)
    void hasAtLeastIsTransitive(
            @ForAll("roles") Role roleA,
            @ForAll("roles") Role roleB,
            @ForAll("roles") Role roleC) {
        if (roleA.hasAtLeast(roleB) && roleB.hasAtLeast(roleC)) {
            assertThat(roleA.hasAtLeast(roleC))
                    .as("Transitivity violated: %s.hasAtLeast(%s) and %s.hasAtLeast(%s) but not %s.hasAtLeast(%s)",
                            roleA, roleB, roleB, roleC, roleA, roleC)
                    .isTrue();
        }
    }

    /**
     * Property: Reflexivity — every role has at least itself.
     *
     * Validates: Requirements 6.2
     */
    @Property(tries = 100)
    void hasAtLeastIsReflexive(@ForAll("roles") Role role) {
        assertThat(role.hasAtLeast(role))
                .as("Role %s should have at least itself", role)
                .isTrue();
    }

    /**
     * Property: The privilege ordering matches the defined hierarchy:
     * Read_Only_Auditor < Skill_Consumer < Skill_Author < Reviewer < Team_Owner < Catalog_Admin < Org_Admin
     *
     * Validates: Requirements 6.2
     */
    @Property(tries = 1)
    void privilegeOrderingMatchesDefinedHierarchy() {
        Role[] expectedOrder = {
                Role.Read_Only_Auditor,
                Role.Skill_Consumer,
                Role.Skill_Author,
                Role.Reviewer,
                Role.Team_Owner,
                Role.Catalog_Admin,
                Role.Org_Admin
        };

        for (int i = 0; i < expectedOrder.length - 1; i++) {
            Role lower = expectedOrder[i];
            Role higher = expectedOrder[i + 1];
            assertThat(lower.getPrivilege())
                    .as("%s should have lower privilege than %s", lower, higher)
                    .isLessThan(higher.getPrivilege());
        }
    }

    @net.jqwik.api.Provide
    net.jqwik.api.Arbitrary<Role> roles() {
        return net.jqwik.api.Arbitraries.of(Role.values());
    }
}
