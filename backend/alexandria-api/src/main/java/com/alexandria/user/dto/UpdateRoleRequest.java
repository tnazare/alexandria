package com.alexandria.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request DTO for changing a user's organization-level role.
 */
public record UpdateRoleRequest(
        @NotNull
        @Pattern(regexp = "Org_Admin|Catalog_Admin|Reviewer|Team_Owner|Skill_Author|Skill_Consumer|Read_Only_Auditor",
                message = "Invalid role: must be one of Org_Admin, Catalog_Admin, Reviewer, Team_Owner, Skill_Author, Skill_Consumer, Read_Only_Auditor")
        String role
) {
}
