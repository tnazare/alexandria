package com.alexandria.skill.lifecycle;

import com.alexandria.auth.AlexandriaUserDetails;
import com.alexandria.skill.entity.SkillEntity;

import java.util.UUID;

/**
 * Governance lifecycle transitions for skills: deprecate, block, archive, and restore.
 *
 * <p>Transitions are skill-level (they do not create a new version), enforce the
 * {@link SkillLifecycleTransition} state machine, capture the prior status for reversible
 * restore, and record an audit event. Authorization (Catalog_Admin/Org_Admin) is enforced at
 * the controller layer; these methods enforce organization scoping and transition legality.</p>
 */
public interface SkillLifecycleService {

    /**
     * Marks a skill as {@code Deprecated}, storing an optional reason and the prior status.
     *
     * @throws com.alexandria.skill.SkillNotFoundException if the skill is not in the user's org
     * @throws com.alexandria.skill.SkillConflictException if deprecation is not legal from the
     *                                                     skill's current status
     */
    SkillEntity deprecate(UUID skillId, String reason, AlexandriaUserDetails user);

    /**
     * Marks a skill as {@code Blocked}, preserving the original active prior status.
     */
    SkillEntity block(UUID skillId, AlexandriaUserDetails user);

    /**
     * Marks a skill as {@code Archived}.
     */
    SkillEntity archive(UUID skillId, AlexandriaUserDetails user);

    /**
     * Restores a governed skill to its recorded prior status (or {@code Draft} if none),
     * clearing the deprecation reason and prior status.
     */
    SkillEntity restore(UUID skillId, AlexandriaUserDetails user);
}
