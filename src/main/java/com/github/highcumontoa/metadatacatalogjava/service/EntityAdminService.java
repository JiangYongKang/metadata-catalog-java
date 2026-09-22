package com.github.highcumontoa.metadatacatalogjava.service;

import com.github.highcumontoa.metadatacatalogjava.model.ActionType;
import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.EntityDef;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;
import com.github.highcumontoa.metadatacatalogjava.store.LocalMetadataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 实体废弃与移除服务。
 * 写操作同样经过权限校验；废弃后实体的所有登记 / 变更 / 血缘写操作均被拒绝。
 * 移除时级联清理全部关联血缘边并审计清理条数，保证无悬空血缘。
 */
@Service
public class EntityAdminService {

    private static final Logger log = LoggerFactory.getLogger(EntityAdminService.class);

    private final LocalMetadataStore store;
    private final PermissionService permissionService;

    public EntityAdminService(LocalMetadataStore store, PermissionService permissionService) {
        this.store = store;
        this.permissionService = permissionService;
    }

    /** 标记废弃。返回 NONE 表示成功（NO_CHANGE 表示此前已废弃），否则为拒绝原因。 */
    public RejectReason deprecate(String operator, String qualifiedName) {
        log.info("[DEPRECATE] 输入 operator={}, qualifiedName={}", operator, qualifiedName);
        if (isBlank(operator) || isBlank(qualifiedName)) {
            return auditReject(ActionType.DEPRECATE_ENTITY, operator, qualifiedName,
                    RejectReason.INVALID_PAYLOAD, "operator 与 qualifiedName 均为必填项");
        }
        var lock = store.lockFor(qualifiedName);
        lock.lock();
        try {
            EntityDef entity = store.getEntity(qualifiedName);
            if (entity == null) {
                return auditReject(ActionType.DEPRECATE_ENTITY, operator, qualifiedName,
                        RejectReason.UNKNOWN_ENTITY, "实体不存在：" + qualifiedName);
            }
            if (!permissionService.canWrite(qualifiedName, operator)) {
                return auditReject(ActionType.DEPRECATE_ENTITY, operator, qualifiedName,
                        RejectReason.PERMISSION_DENIED,
                        "操作者 '" + operator + "' 对实体 " + qualifiedName + " 无修改权限");
            }
            if (entity.isDeprecated()) {
                store.appendAudit(ActionType.DEPRECATE_ENTITY, operator, qualifiedName,
                        Decision.NO_CHANGE, RejectReason.NONE, "实体此前已废弃，幂等返回");
                log.info("[DEPRECATE] qualifiedName={} decision=NO_CHANGE", qualifiedName);
                return RejectReason.NONE;
            }
            entity.markDeprecated();
            store.appendAudit(ActionType.DEPRECATE_ENTITY, operator, qualifiedName,
                    Decision.ACCEPTED, RejectReason.NONE, "实体已标记废弃，此后一切写操作将被拒绝");
            log.info("[DEPRECATE] qualifiedName={} decision=ACCEPTED", qualifiedName);
            return RejectReason.NONE;
        } finally {
            lock.unlock();
        }
    }

    /** 移除实体并级联清理血缘。返回 NONE 表示成功，否则为拒绝原因。 */
    public RejectReason remove(String operator, String qualifiedName) {
        log.info("[REMOVE] 输入 operator={}, qualifiedName={}", operator, qualifiedName);
        if (isBlank(operator) || isBlank(qualifiedName)) {
            return auditReject(ActionType.REMOVE_ENTITY, operator, qualifiedName,
                    RejectReason.INVALID_PAYLOAD, "operator 与 qualifiedName 均为必填项");
        }
        var entityLock = store.lockFor(qualifiedName);
        // 固定加锁顺序：先实体锁后血缘锁，避免与血缘登记路径互相等待
        entityLock.lock();
        store.lineageLock().lock();
        try {
            EntityDef entity = store.getEntity(qualifiedName);
            if (entity == null) {
                return auditReject(ActionType.REMOVE_ENTITY, operator, qualifiedName,
                        RejectReason.UNKNOWN_ENTITY, "实体不存在：" + qualifiedName);
            }
            if (!permissionService.canWrite(qualifiedName, operator)) {
                return auditReject(ActionType.REMOVE_ENTITY, operator, qualifiedName,
                        RejectReason.PERMISSION_DENIED,
                        "操作者 '" + operator + "' 对实体 " + qualifiedName + " 无修改权限");
            }
            int cleaned = store.removeEntityAndLineage(qualifiedName);
            store.appendAudit(ActionType.REMOVE_ENTITY, operator, qualifiedName,
                    Decision.ACCEPTED, RejectReason.NONE,
                    "实体已移除，并级联清理血缘边 " + cleaned + " 条，无悬空血缘");
            log.info("[REMOVE] qualifiedName={} decision=ACCEPTED cleanedEdges={}", qualifiedName, cleaned);
            return RejectReason.NONE;
        } finally {
            store.lineageLock().unlock();
            entityLock.unlock();
        }
    }

    private RejectReason auditReject(ActionType action, String operator, String qualifiedName,
                                     RejectReason reason, String message) {
        store.appendAudit(action, operator, qualifiedName, Decision.REJECTED, reason, message);
        log.info("{} qualifiedName={} decision=REJECTED reason={} 依据={}",
                action, qualifiedName, reason, message);
        return reason;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
