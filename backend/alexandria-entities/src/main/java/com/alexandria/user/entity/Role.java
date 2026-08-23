package com.alexandria.user.entity;

/**
 * Organization-level roles in ascending privilege order.
 * Each role has a numeric privilege level used for comparison.
 */
public enum Role {

    Read_Only_Auditor(0),
    Skill_Consumer(1),
    Skill_Author(2),
    Reviewer(3),
    Team_Owner(4),
    Catalog_Admin(5),
    Org_Admin(6);

    private final int privilege;

    Role(int privilege) {
        this.privilege = privilege;
    }

    public int getPrivilege() {
        return privilege;
    }

    /**
     * Returns true if this role has at least the same privilege level as the given role.
     */
    public boolean hasAtLeast(Role other) {
        return this.privilege >= other.privilege;
    }

    /**
     * Returns true if this role is an administrative role (Catalog_Admin or Org_Admin).
     */
    public boolean isAdmin() {
        return this == Catalog_Admin || this == Org_Admin;
    }
}
