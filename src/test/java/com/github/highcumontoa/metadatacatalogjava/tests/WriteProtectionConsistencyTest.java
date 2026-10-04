package com.github.highcumontoa.metadatacatalogjava.tests;

import com.github.highcumontoa.metadatacatalogjava.dto.ChangeFieldOp;
import com.github.highcumontoa.metadatacatalogjava.dto.FieldClaim;
import com.github.highcumontoa.metadatacatalogjava.dto.LineageRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.RegistrationRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.SchemaChangeRequest;
import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;
import com.github.highcumontoa.metadatacatalogjava.service.CompatibilityService;
import com.github.highcumontoa.metadatacatalogjava.service.EntityAdminService;
import com.github.highcumontoa.metadatacatalogjava.service.LineageService;
import com.github.highcumontoa.metadatacatalogjava.service.PermissionService;
import com.github.highcumontoa.metadatacatalogjava.service.RegistrationService;
import com.github.highcumontoa.metadatacatalogjava.service.TypeArbitrator;
import com.github.highcumontoa.metadatacatalogjava.service.TypeDictionary;
import com.github.highcumontoa.metadatacatalogjava.store.LocalMetadataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 写保护一致性测试：废弃与只读（白名单）状态下，登记 / 字段变更 / 血缘登记 / 废弃 / 移除
 * 五个写入口统一拒绝且原因可区分；实体不存在与字段不存在明确区分。
 * 每次输入与判定依据均通过日志打印。
 */
class WriteProtectionConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(WriteProtectionConsistencyTest.class);

    private LocalMetadataStore store;
    private PermissionService permissions;
    private RegistrationService registration;
    private CompatibilityService compatibility;
    private LineageService lineage;
    private EntityAdminService admin;

    @BeforeEach
    void setUp() {
        store = new LocalMetadataStore();
        var types = new TypeDictionary();
        permissions = new PermissionService();
        registration = new RegistrationService(store, types, permissions, new TypeArbitrator());
        compatibility = new CompatibilityService(store, types, permissions);
        lineage = new LineageService(store, permissions);
        admin = new EntityAdminService(store, permissions);
        registration.register(new RegistrationRequest("hive", "db.a", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        registration.register(new RegistrationRequest("hive", "db.b", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
    }

    @Test
    @DisplayName("已废弃实体：五个写入口统一以 ENTITY_DEPRECATED 拒绝")
    void deprecatedEntityRejectsAllWriteEntries() {
        assertEquals(RejectReason.NONE, admin.deprecate("admin", "db.a"));

        var reg = registration.register(new RegistrationRequest("mysql", "db.a", "TABLE",
                List.of(new FieldClaim("x", "string"))));
        log.info("废弃后登记 -> decision={}, reason={}", reg.decision(), reg.reason());
        assertEquals(RejectReason.ENTITY_DEPRECATED, reg.reason());

        var change = compatibility.apply(new SchemaChangeRequest("admin", "db.a",
                List.of(new ChangeFieldOp("ADD", "x", "string"))));
        log.info("废弃后字段变更 -> decision={}, reason={}", change.decision(), change.reason());
        assertEquals(RejectReason.ENTITY_DEPRECATED, change.reason());

        var linOut = lineage.registerLineage(new LineageRequest("hive", "db.a", "db.b"));
        var linIn = lineage.registerLineage(new LineageRequest("hive", "db.b", "db.a"));
        log.info("废弃后血缘（作为上游） -> reason={}；（作为下游） -> reason={}",
                linOut.reason(), linIn.reason());
        assertEquals(RejectReason.ENTITY_DEPRECATED, linOut.reason());
        assertEquals(RejectReason.ENTITY_DEPRECATED, linIn.reason());

        var again = admin.deprecate("admin", "db.a");
        log.info("废弃后重复废弃 -> reason={}", again);
        assertEquals(RejectReason.ENTITY_DEPRECATED, again);

        var remove = admin.remove("admin", "db.a");
        log.info("废弃后移除 -> reason={}", remove);
        assertEquals(RejectReason.ENTITY_DEPRECATED, remove);

        // 实体仍在，未被移除
        assertEquals(true, store.getEntity("db.a") != null && store.getEntity("db.a").isDeprecated());
    }

    @Test
    @DisplayName("只读授权状态（白名单外操作者）：五个写入口统一以 PERMISSION_DENIED 拒绝，且与废弃原因可区分")
    void readOnlyOperatorRejectsAllWriteEntries() {
        // 进入白名单模式：仅 bob（与 admin）可写，carol 只读
        permissions.grant("db.a", "bob");
        permissions.grant("db.b", "bob");

        var reg = registration.register(new RegistrationRequest("carol", "db.a", "TABLE",
                List.of(new FieldClaim("x", "string"))));
        log.info("只读操作者登记 -> reason={}", reg.reason());
        assertEquals(RejectReason.PERMISSION_DENIED, reg.reason());

        var change = compatibility.apply(new SchemaChangeRequest("carol", "db.a",
                List.of(new ChangeFieldOp("ADD", "x", "string"))));
        log.info("只读操作者字段变更 -> reason={}", change.reason());
        assertEquals(RejectReason.PERMISSION_DENIED, change.reason());

        var lin = lineage.registerLineage(new LineageRequest("carol", "db.a", "db.b"));
        log.info("只读操作者血缘登记 -> reason={}", lin.reason());
        assertEquals(RejectReason.PERMISSION_DENIED, lin.reason());

        var dep = admin.deprecate("carol", "db.a");
        log.info("只读操作者废弃 -> reason={}", dep);
        assertEquals(RejectReason.PERMISSION_DENIED, dep);

        var remove = admin.remove("carol", "db.a");
        log.info("只读操作者移除 -> reason={}", remove);
        assertEquals(RejectReason.PERMISSION_DENIED, remove);

        // 与废弃原因明确区分
        assertNotEquals(RejectReason.ENTITY_DEPRECATED, reg.reason());
        // 白名单内操作者仍可写，证明拒绝确系只读授权状态
        var ok = compatibility.apply(new SchemaChangeRequest("bob", "db.a",
                List.of(new ChangeFieldOp("ADD", "x", "string"))));
        log.info("白名单操作者 bob 字段变更 -> decision={}", ok.decision());
        assertEquals(Decision.MERGED, ok.decision());
    }

    @Test
    @DisplayName("实体不存在与字段不存在明确区分：UNKNOWN_ENTITY vs UNKNOWN_FIELD")
    void unknownEntityAndFieldAreDistinct() {
        // 变更：实体不存在 -> UNKNOWN_ENTITY；字段不存在 -> UNKNOWN_FIELD
        var ghostChange = compatibility.apply(new SchemaChangeRequest("alice", "db.ghost",
                List.of(new ChangeFieldOp("ADD", "x", "string"))));
        log.info("不存在实体的变更 -> reason={}", ghostChange.reason());
        assertEquals(RejectReason.UNKNOWN_ENTITY, ghostChange.reason());

        var missingFieldAlter = compatibility.apply(new SchemaChangeRequest("alice", "db.a",
                List.of(new ChangeFieldOp("ALTER_TYPE", "no_such_col", "bigint"))));
        log.info("不存在字段的 ALTER_TYPE -> reason={}", missingFieldAlter.reason());
        assertEquals(RejectReason.UNKNOWN_FIELD, missingFieldAlter.reason());

        var missingFieldDrop = compatibility.apply(new SchemaChangeRequest("alice", "db.a",
                List.of(new ChangeFieldOp("DROP", "no_such_col", null))));
        log.info("不存在字段的 DROP -> reason={}", missingFieldDrop.reason());
        assertEquals(RejectReason.UNKNOWN_FIELD, missingFieldDrop.reason());

        // 血缘：端点不存在 -> UNKNOWN_ENTITY
        var lin = lineage.registerLineage(new LineageRequest("hive", "db.a", "db.ghost"));
        log.info("不存在端点的血缘 -> reason={}", lin.reason());
        assertEquals(RejectReason.UNKNOWN_ENTITY, lin.reason());

        // 废弃 / 移除：实体不存在 -> UNKNOWN_ENTITY
        var dep = admin.deprecate("admin", "db.ghost");
        var remove = admin.remove("admin", "db.ghost");
        log.info("不存在实体的废弃 -> reason={}；移除 -> reason={}", dep, remove);
        assertEquals(RejectReason.UNKNOWN_ENTITY, dep);
        assertEquals(RejectReason.UNKNOWN_ENTITY, remove);

        // 四类原因两两可区分：实体不存在 / 字段不存在 / 类型不合法 / 无权限
        var badType = compatibility.apply(new SchemaChangeRequest("alice", "db.a",
                List.of(new ChangeFieldOp("ADD", "y", "jsonb"))));
        permissions.grant("db.a", "bob");
        var denied = compatibility.apply(new SchemaChangeRequest("carol", "db.a",
                List.of(new ChangeFieldOp("ADD", "z", "string"))));
        log.info("类型不合法 -> reason={}；无权限 -> reason={}", badType.reason(), denied.reason());
        assertEquals(RejectReason.UNKNOWN_TYPE, badType.reason());
        assertEquals(RejectReason.PERMISSION_DENIED, denied.reason());
        assertNotEquals(ghostChange.reason(), missingFieldAlter.reason());
        assertNotEquals(missingFieldAlter.reason(), badType.reason());
        assertNotEquals(badType.reason(), denied.reason());
    }
}
