package com.github.highcumontoa.metadatacatalogjava.service;

import com.github.highcumontoa.metadatacatalogjava.model.AuditRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only audit trail stored locally in memory.
 * Successful changes and rejections are both recorded, each with the
 * decision basis. Sequence numbers are assigned atomically so that
 * concurrent writers get a total order.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final CopyOnWriteArrayList<AuditRecord> records = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong(0);

    public AuditRecord record(String action, String target, String actor,
                              boolean allowed, String reason, String detail) {
        AuditRecord rec = new AuditRecord(
                sequence.incrementAndGet(),
                Instant.now(),
                action,
                target,
                actor == null ? "anonymous" : actor,
                allowed,
                reason,
                detail);
        records.add(rec);
        log.info("AUDIT seq={} action={} target={} actor={} allowed={} reason={} detail={}",
                rec.seq(), rec.action(), rec.target(), rec.actor(),
                rec.allowed(), rec.reason(), rec.detail());
        return rec;
    }

    public List<AuditRecord> all() {
        return List.copyOf(records);
    }

    public List<AuditRecord> byEntity(String qualifiedName) {
        String q = MetadataCatalogService.normalize(qualifiedName);
        return records.stream()
                .filter(r -> r.target() != null && r.target().contains(q))
                .toList();
    }
}
