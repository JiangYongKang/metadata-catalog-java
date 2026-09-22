package com.github.highcumontoa.metadatacatalogjava.tests;

import com.github.highcumontoa.metadatacatalogjava.dto.ChangeFieldOp;
import com.github.highcumontoa.metadatacatalogjava.dto.FieldClaim;
import com.github.highcumontoa.metadatacatalogjava.dto.RegistrationRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.SchemaChangeRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.SchemaChangeResult;
import com.github.highcumontoa.metadatacatalogjava.model.AuditRecord;
import com.github.highcumontoa.metadatacatalogjava.model.DataType;
import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;
import com.github.highcumontoa.metadatacatalogjava.service.CompatibilityService;
import com.github.highcumontoa.metadatacatalogjava.service.EntityAdminService;
import com.github.highcumontoa.metadatacatalogjava.service.PermissionService;
import com.github.highcumontoa.metadatacatalogjava.service.RegistrationService;
import com.github.highcumontoa.metadatacatalogjava.service.TypeDictionary;
import com.github.highcumontoa.metadatacatalogjava.store.LocalMetadataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 兼容判定单测：新增 / 删除 / 类型变更策略、原子性、权限与废弃拒绝、审计留痕。
 * 输入与判定依据通过 [CHANGE] 日志打印。
 */
class CompatibilityServiceTest {

    private static final Logger log = LoggerFactory.getLogger(CompatibilityServiceTest.class);

    private LocalMetadataStore store;
    private RegistrationService registration;
    private CompatibilityService compatibility;
    private PermissionService permissions;
    private EntityAdminService admin;

    @BeforeEach
    void setUp() {
        store = new LocalMetadataStore();
        var types = new TypeDictionary();
        permissions = new PermissionService();
        registration = new RegistrationService(store, types, permissions);
        compatibility = new CompatibilityService(store, types, permissions);
        admin = new EntityAdminService(store, permissions);
        registration.register(new RegistrationRequest("hive", "db.t", "TABLE", List.of(
                new FieldClaim("id", "int"),
                new FieldClaim("amount", "bigint"),
                new FieldClaim("name", "string"))));
    }

    @Test
    @DisplayName("新增字段：兼容并生效")
    void addFieldIsCompatible() {
        var r = compatibility.apply(new SchemaChangeRequest("alice", "db.t",
                List.of(new ChangeFieldOp("ADD", "remark", "text"))));
        log.info("用例输入 ADD remark:text -> decision={}, 依据={}", r.decision(), r.opResults());
        assertEquals(Decision.MERGED, r.decision());
        assertEquals(DataType.STRING, store.getEntity("db.t").getFields().get("remark").getCanonicalType());
    }

    @Test
    @DisplayName("删除字段：FIELD_REMOVED_INCOMPATIBLE 拒绝且字段保留")
    void dropFieldIsBreaking() {
        var r = compatibility.apply(new SchemaChangeRequest("alice", "db.t",
                List.of(new ChangeFieldOp("DROP", "name", null))));
        log.info("用例输入 DROP name -> decision={}, reason={}, 依据={}",
                r.decision(), r.reason(), r.message());
        assertEquals(Decision.REJECTED, r.decision());
        assertEquals(RejectReason.FIELD_REMOVED_INCOMPATIBLE, r.reason());
        assertTrue(store.getEntity("db.t").getFields().containsKey("name"), "字段不得被删除");
    }

    @Test
    @DisplayName("类型放宽 INTEGER->BIGINT：兼容并生效")
    void wideningTypeIsCompatible() {
        var r = compatibility.apply(new SchemaChangeRequest("alice", "db.t",
                List.of(new ChangeFieldOp("ALTER_TYPE", "id", "bigint"))));
        log.info("用例输入 ALTER id:int->bigint -> decision={}, 依据={}", r.decision(), r.opResults());
        assertEquals(Decision.MERGED, r.decision());
        assertEquals(DataType.BIGINT, store.getEntity("db.t").getFields().get("id").getCanonicalType());
    }

    @Test
    @DisplayName("类型收窄 BIGINT->INTEGER：TYPE_CHANGE_INCOMPATIBLE 拒绝且类型不变")
    void narrowingTypeIsBreaking() {
        var r = compatibility.apply(new SchemaChangeRequest("alice", "db.t",
                List.of(new ChangeFieldOp("ALTER_TYPE", "amount", "int"))));
        log.info("用例输入 ALTER amount:bigint->int -> decision={}, reason={}, 依据={}",
                r.decision(), r.reason(), r.opResults().get(0).detail());
        assertEquals(Decision.REJECTED, r.decision());
        assertEquals(RejectReason.TYPE_CHANGE_INCOMPATIBLE, r.reason());
        assertEquals(DataType.BIGINT, store.getEntity("db.t").getFields().get("amount").getCanonicalType());
    }

    @Test
    @DisplayName("跨族类类型变更 STRING->INTEGER：破坏性拒绝")
    void crossFamilyChangeIsBreaking() {
        var r = compatibility.apply(new SchemaChangeRequest("alice", "db.t",
                List.of(new ChangeFieldOp("ALTER_TYPE", "name", "int"))));
        log.info("用例输入 ALTER name:string->int -> reason={}, 依据={}",
                r.reason(), r.opResults().get(0).detail());
        assertEquals(RejectReason.TYPE_CHANGE_INCOMPATIBLE, r.reason());
        assertEquals(DataType.STRING, store.getEntity("db.t").getFields().get("name").getCanonicalType());
    }

    @Test
    @DisplayName("同一批次中兼容操作与破坏性操作混合：整批原子拒绝，兼容部分也不生效")
    void mixedBatchIsAtomic() {
        var r = compatibility.apply(new SchemaChangeRequest("alice", "db.t", List.of(
                new ChangeFieldOp("ADD", "new_col", "string"),
                new ChangeFieldOp("DROP", "name", null),
                new ChangeFieldOp("ALTER_TYPE", "id", "bigint"))));
        log.info("用例混合批次 ADD+DROP+ALTER -> decision={}, reasons={}",
                r.decision(), r.opResults().stream().map(x -> x.fieldName() + ":" + x.reason()).toList());
        assertEquals(Decision.REJECTED, r.decision());
        assertEquals(RejectReason.FIELD_REMOVED_INCOMPATIBLE, r.reason());
        assertFalse(store.getEntity("db.t").getFields().containsKey("new_col"), "整批回滚，新字段不得落库");
        assertEquals(DataType.INTEGER, store.getEntity("db.t").getFields().get("id").getCanonicalType(),
                "整批回滚，放宽变更同样不得落库");
    }

    @Test
    @DisplayName("目标类型非法：UNKNOWN_TYPE 原因可区分")
    void unknownTypeRejected() {
        var r = compatibility.apply(new SchemaChangeRequest("alice", "db.t",
                List.of(new ChangeFieldOp("ADD", "x", "jsonb"))));
        log.info("用例输入 ADD x:jsonb -> reason={}", r.reason());
        assertEquals(RejectReason.UNKNOWN_TYPE, r.reason());
    }

    @Test
    @DisplayName("无修改权限：PERMISSION_DENIED 拒绝，拒绝原因与废弃原因可区分")
    void permissionDenied() {
        // 将 db.t 置入白名单模式：只有 admin 与 bob 可写
        permissions.grant("db.t", "bob");
        var r = compatibility.apply(new SchemaChangeRequest("carol", "db.t",
                List.of(new ChangeFieldOp("ADD", "x", "string"))));
        log.info("用例操作者 carol（非白名单） -> decision={}, reason={}", r.decision(), r.reason());
        assertEquals(Decision.REJECTED, r.decision());
        assertEquals(RejectReason.PERMISSION_DENIED, r.reason());
        assertFalse(store.getEntity("db.t").getFields().containsKey("x"));

        // 白名单内 bob 可以成功，证明原因确系权限
        var ok = compatibility.apply(new SchemaChangeRequest("bob", "db.t",
                List.of(new ChangeFieldOp("ADD", "x", "string"))));
        log.info("用例操作者 bob（白名单） -> decision={}", ok.decision());
        assertEquals(Decision.MERGED, ok.decision());
    }

    @Test
    @DisplayName("已废弃实体写操作：ENTITY_DEPRECATED 拒绝，原因区别于权限拒绝")
    void deprecatedEntityRejectsWrites() {
        assertEquals(RejectReason.NONE, admin.deprecate("admin", "db.t"));
        var r = compatibility.apply(new SchemaChangeRequest("admin", "db.t",
                List.of(new ChangeFieldOp("ADD", "x", "string"))));
        log.info("用例对已废弃实体发起变更（admin） -> decision={}, reason={}", r.decision(), r.reason());
        assertEquals(Decision.REJECTED, r.decision());
        assertEquals(RejectReason.ENTITY_DEPRECATED, r.reason());
    }

    @Test
    @DisplayName("所有变更（含拒绝）均留下可追溯审计记录")
    void auditTrailIsAppended() {
        compatibility.apply(new SchemaChangeRequest("alice", "db.t",
                List.of(new ChangeFieldOp("DROP", "name", null))));
        compatibility.apply(new SchemaChangeRequest("alice", "db.t",
                List.of(new ChangeFieldOp("ADD", "note", "string"))));
        List<AuditRecord> trail = store.auditTrail("db.t").stream()
                .filter(x -> x.action() == com.github.highcumontoa.metadatacatalogjava.model.ActionType.CHANGE_SCHEMA)
                .toList();
        log.info("用例变更审计轨迹（拒绝+接受各一）={}",
                trail.stream().map(x -> x.sequence() + ":" + x.decision() + ":" + x.reason()).toList());
        assertEquals(2, trail.size());
        assertEquals(Decision.REJECTED, trail.get(0).decision());
        assertEquals(RejectReason.FIELD_REMOVED_INCOMPATIBLE, trail.get(0).reason());
        assertEquals(Decision.MERGED, trail.get(1).decision());
        assertTrue(trail.get(0).sequence() < trail.get(1).sequence());
    }
}
