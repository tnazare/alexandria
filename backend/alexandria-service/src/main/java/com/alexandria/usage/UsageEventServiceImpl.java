package com.alexandria.usage;

import com.alexandria.usage.entity.UsageEventEntity;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Fire-and-forget implementation of {@link UsageEventService}.
 *
 * <p>Any exception during persistence is caught, logged at ERROR level with the event details,
 * and swallowed, so usage-tracking failures never propagate to callers.</p>
 */
@Service
@RequiredArgsConstructor
public class UsageEventServiceImpl implements UsageEventService {

    private static final Logger log = LoggerFactory.getLogger(UsageEventServiceImpl.class);

    private final UsageEventRepository usageEventRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID orgId, UUID actorUserId, UUID skillId, UUID skillVersionId,
                       String eventType, String source, String exportFormat) {
        try {
            UsageEventEntity entity = new UsageEventEntity();
            entity.setOrganizationId(orgId);
            entity.setUserId(actorUserId);
            entity.setSkillId(skillId);
            entity.setSkillVersionId(skillVersionId);
            entity.setEventType(eventType);
            entity.setSource(source);
            entity.setExportFormat(exportFormat);

            usageEventRepository.save(entity);
        } catch (Exception ex) {
            log.error("Failed to record usage event: orgId={}, actorUserId={}, skillId={}, "
                            + "skillVersionId={}, eventType={}, source={}, exportFormat={}",
                    orgId, actorUserId, skillId, skillVersionId, eventType, source, exportFormat, ex);
        }
    }
}
