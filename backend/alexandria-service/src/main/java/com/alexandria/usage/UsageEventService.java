package com.alexandria.usage;

import java.util.UUID;

/**
 * Service for recording append-only usage events (copy/export, and later views).
 *
 * <p>Recording uses a fire-and-forget pattern — failures are logged at ERROR level but never
 * propagate — so usage-tracking issues cannot disrupt or fail an export.</p>
 */
public interface UsageEventService {

    /**
     * Records a usage event. Never throws.
     *
     * @param orgId          the organization in which the action occurred
     * @param actorUserId    the acting user (may be null)
     * @param skillId        the skill acted on
     * @param skillVersionId the resolved target version (may be null)
     * @param eventType      {@code copy} or {@code export} (later also {@code view})
     * @param source         {@code copy} or {@code download} as sent by the client (may be null)
     * @param exportFormat   the export format wire value (may be null)
     */
    void record(UUID orgId, UUID actorUserId, UUID skillId, UUID skillVersionId,
                String eventType, String source, String exportFormat);
}
