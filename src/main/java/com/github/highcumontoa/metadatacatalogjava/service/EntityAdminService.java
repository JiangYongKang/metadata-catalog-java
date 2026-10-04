package com.github.highcumontoa.metadatacatalogjava.service;

import com.github.highcumontoa.metadatacatalogjava.model.ActionType;
import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.EntityDef;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;
import com.github.highcumontoa.metadatacatalogjava.store.LocalMetadataStore;
import com.github.highcumontoa.metadatacatalogjava.util.Names;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 实体废弃与移除服务。
 *
 * <p>写保护与所有写入口保持一致：检查顺序为“必填 -&gt; 实体存在 -&gt; 已废弃 -&gt; 权限 -&gt; 业务规则”，
 * 实体一旦废弃，包括 admin 在内的任何操作者再对其执行废弃 / 移除等写操作一律得到
 * ENTITY_DEPRECATED（与 PERMISSION_DENIED / UNKNOWN_ENTITY 可区分）；
 * 废弃是幂等状态：重复废弃一个已经废弃的实体同样以 ENTITY_DEPRECATED 拒绝。
 * 移除时级联清理全部关联血缘边并审计清理条数，保证无悬空血缘。</p>
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

    /** 标记废弃。返回 NONE 表示成功；否则为拒绝原因（已废弃 -> ENTITY_DEPRECATED，即使 admin 也拒绝）。 */
    public RejectReason deprecate(String operator, String qualifiedName) {
        log.info("[DEPRECATE] 输入 operator={}, qualifiedName={}", operator, qualifiedName);
        if (isBlank(operator) || isBlank(qualifiedName)) {
            return auditReject(ActionType.DEPRECATE_ENTITY, operator, Names.canonical(qualifiedName),
                    RejectReason.INVALID_PAYLOAD, "operator 与 qualifiedName 均为必填项");
        }
        String qn = Names.canonical(qualifiedName);
        operator = operator.trim();
        var lock = store.lockFor(qn);
        lock.lock();
        try {
            EntityDef entity = store.getEntity(qn);
            if (entity == null) {
                return auditReject(ActionType.DEPRECATE_ENTITY, operator, qn,
                        RejectReason.UNKNOWN_ENTITY, "实体不存在：" + qn);
            }
            if (entity.isDeprecated()) {
                return auditReject(ActionType.DEPRECATE_ENTITY, operator, qn,
                        RejectReason.ENTITY_DEPRECATED,
                        "实体 " + qn + " 已废弃，拒绝一切写操作（即使 admin 也不例外）");
            }
            if (!permissionService.canWrite(qn, operator)) {
                return auditReject(ActionType.DEPRECATE_ENTITY, operator, qn,
                        RejectReason.PERMISSION_DENIED,
                        "操作者 '" + operator + "' 对实体 " + qn + " 无修改权限");
            }
            entity.markDeprecated();
            store.appendAudit(ActionType.DEPRECATE_ENTITY, operator, qn,
                    Decision.ACCEPTED, RejectReason.NONE, "实体已标记废弃，此后一切写操作将被拒绝");
            log.info("[DEPRECATE] qualifiedName={} decision=ACCEPTED", qn);
            return RejectReason.NONE;
        } finally {
            lock.unlock();
        }
    }

    /** 移除实体并级联清理血缘。返回 NONE 表示成功，否则为拒绝原因。 */
    public RejectReason remove(String operator, String qualifiedName) {
        log.info("[REMOVE] 输入 operator={}, qualifiedName={}", operator, qualifiedName);
        if (isBlank(operator) || isBlank(qualifiedName)) {
            return auditReject(ActionType.REMOVE_ENTITY, operator, Names.canonical(qualifiedName),
                    RejectReason.INVALID_PAYLOAD, "operator 与 qualifiedName 均为必填项");
        }
        String qn = Names.canonical(qualifiedName);
        operator = operator.trim();
        var entityLock = store.lockFor(qn);
        // 固定加锁顺序：先实体锁后血缘锁，避免与血缘登记路径互相等待
        entityLock.lock();
        store.lineageLock().lock();
        try {
            EntityDef entity = store.getEntity(qn);
            if (entity == null) {
                return auditReject(ActionType.REMOVE_ENTITY, operator, qn,
                        RejectReason.UNKNOWN_ENTITY, "实体不存在：" + qn);
            }
            if (entity.isDeprecated()) {
                return auditReject(ActionType.REMOVE_ENTITY, operator, qn,
                        RejectReason.ENTITY_DEPRECATED,
                        "实体 " + qn + " 已废弃，拒绝一切写操作（包括移除，即使 admin 也不例外）");
            }
            if (!permissionService.canWrite(qn, operator)) {
                return auditReject(ActionType.REMOVE_ENTITY, operator, qn,
                        RejectReason.PERMISSION_DENIED,
                        "操作者 '" + operator + "' 对实体 " + qn + " 无修改权限");
            }
            int cleaned = store.removeEntityAndLineage(qn);
            store.appendAudit(ActionType.REMOVE_ENTITY, operator, qn,
                    Decision.ACCEPTED, RejectReason.NONE,
                    "实体已移除，并级联清理血缘边 " + cleaned + " 条，无悬空血缘");
            log.info("[REMOVE] qualifiedName={} decision=ACCEPTED cleanedEdges={}", qn, cleaned);
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
