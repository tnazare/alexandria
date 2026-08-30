package com.alexandria.skill.lifecycle;

import java.util.Set;

/**
 * The single source of truth for skill lifecycle governance transition legality.
 *
 * <p>Encodes the state machine from the skill-lifecycle design: which
 * {@link LifecycleAction}s are permitted from which {@code lifecycle_status}, and the
 * target status for the non-restore actions. RESTORE's target is resolved by the service
 * from the skill's recorded prior status, so it has no fixed target here.</p>
 *
 * <p>Legality table:</p>
 * <pre>
 * Current \ Action     DEPRECATE  BLOCK  ARCHIVE  RESTORE
 * Draft                   yes      yes     yes       no
 * Published               yes      yes     yes       no
 * Team_Approved           yes      yes     yes       no
 * Company_Approved        yes      yes     yes       no
 * Deprecated              no       yes     yes       yes
 * Archived                no       yes     no        yes
 * Blocked                 no       no      no        yes
 * </pre>
 */
public final class SkillLifecycleTransition {

    public static final String DRAFT = "Draft";
    public static final String PUBLISHED = "Published";
    public static final String TEAM_APPROVED = "Team_Approved";
    public static final String COMPANY_APPROVED = "Company_Approved";
    public static final String DEPRECATED = "Deprecated";
    public static final String ARCHIVED = "Archived";
    public static final String BLOCKED = "Blocked";

    /** Non-governed ("active") states. */
    public static final Set<String> ACTIVE_STATES =
            Set.of(DRAFT, PUBLISHED, TEAM_APPROVED, COMPANY_APPROVED);

    /** Governed states — the states this milestone transitions into. */
    public static final Set<String> GOVERNED_STATES =
            Set.of(DEPRECATED, ARCHIVED, BLOCKED);

    private SkillLifecycleTransition() {
    }

    /**
     * Returns {@code true} if {@code action} is permitted from {@code currentStatus}.
     * Total over every status/action pair.
     */
    public static boolean isAllowed(String currentStatus, LifecycleAction action) {
        if (currentStatus == null || action == null) {
            return false;
        }
        boolean active = ACTIVE_STATES.contains(currentStatus);
        return switch (action) {
            case DEPRECATE -> active;
            case ARCHIVE -> active || DEPRECATED.equals(currentStatus);
            case BLOCK -> !BLOCKED.equals(currentStatus);
            case RESTORE -> GOVERNED_STATES.contains(currentStatus);
        };
    }

    /**
     * The fixed target status for the non-restore actions. RESTORE is not supported here —
     * its target depends on the skill's recorded prior status and is resolved by the service.
     *
     * @throws IllegalArgumentException if called with RESTORE
     */
    public static String targetStatus(LifecycleAction action) {
        return switch (action) {
            case DEPRECATE -> DEPRECATED;
            case BLOCK -> BLOCKED;
            case ARCHIVE -> ARCHIVED;
            case RESTORE -> throw new IllegalArgumentException(
                    "RESTORE target is resolved from the skill's prior status, not fixed");
        };
    }

    /** Returns true if the given status is one of the governed states. */
    public static boolean isGoverned(String status) {
        return GOVERNED_STATES.contains(status);
    }

    /** Returns true if the given status is one of the active (non-governed) states. */
    public static boolean isActive(String status) {
        return ACTIVE_STATES.contains(status);
    }
}
