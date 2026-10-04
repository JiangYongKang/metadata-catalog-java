package com.github.highcumontoa.metadatacatalogjava.tests;

import com.github.highcumontoa.metadatacatalogjava.dto.ChangeFieldOp;
import com.github.highcumontoa.metadatacatalogjava.dto.FieldClaim;
import com.github.highcumontoa.metadatacatalogjava.dto.LineageRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.RegistrationRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.SchemaChangeRequest;
import com.github.highcumontoa.metadatacatalogjava.model.DataType;
import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.EntityDef;
import com.github.highcumontoa.metadatacatalogjava.model.FieldDef;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;
import com.github.highcumontoa.metadatacatalogjava.service.CompatibilityService;
import com.github.highcumontoa.metadatacatalogjava.service.LineageService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * “归一收敛 + 写入口一致性”新增行为单测。
 * 覆盖：书写差异归一、并发/乱序声明的内容裁决收敛（多轮重复）、种类不一致、
 * 混合批次整体不落库、废弃/只读写保护在全部写入口一致、实体不存在与字段不存在可区分。
 * 输入与判定依据通过 [REGISTER]/[LINEAGE]/[CHANGE]/[DEPRECATE]/[REMOVE] 日志打印。
 */
class WriteEntryConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(WriteEntryConsistencyTest.class);

    private LocalMetadataStore store;
    private RegistrationService registration;
    private CompatibilityService compatibility;
    private LineageService lineage;
    private PermissionService permissions;
    private com.github.highcumontoa.metadatacatalogjava.service.EntityAdminService admin;

    @BeforeEach
    void setUp() {
        store = new LocalMetadataStore();
        var types = new TypeDictionary();
        permissions = new PermissionService();
        registration = new RegistrationService(store, types, permissions);
        compatibility = new CompatibilityService(store, types, permissions);
        lineage = new LineageService(store, permissions);
        admin = new com.github.highcumontoa.metadatacatalogjava.service.EntityAdminService(store, permissions);
    }

    @Test
    @DisplayName("归一：实体名仅大小写/首尾空白不同必须识别为同一实体，不各建一份")
    void entityNameWritingVariantsAreSameObject() {
        var r1 = registration.register(new RegistrationRequest("hive", "  ODS.User ", "table",
                List.of(new FieldClaim("ID", "bigint"))));
        var r2 = registration.register(new RegistrationRequest("mysql", "ods.USER", "TABLE",
                List.of(new FieldClaim(" id ", "LONG"))));
        log.info("用例 ' ODS.User '/'ods.USER' + 'ID'/' id ' -> decisions={},{}", r1.decision(), r2.decision());

        assertEquals(Decision.ACCEPTED, r1.decision());
        assertEquals(Decision.MERGED, r2.decision());
        // 目录里只有一个实体、一个字段
        assertEquals(1, store.allEntities().size(), "书写差异不得各建一份实体");
        EntityDef entity = store.getEntity("ods.user");
        assertNotNull(entity);
        assertEquals(1, entity.getFields().size(), "书写差异不得各建一份字段");
        FieldDef id = entity.getFields().get("id");
        assertEquals(DataType.BIGINT, id.getCanonicalType());
        assertEquals(2, id.getSources().size(), "两种书写是同一字段，来源应合并");
    }

    @Test
    @DisplayName("归一：字段变更入口对字段名大小写/空白差异识别为同一字段")
    void fieldNameWritingVariantsResolveToSameField() {
        registration.register(new RegistrationRequest("hive", "ods.t", "TABLE",
                List.of(new FieldClaim("Amount", "int"))));
        // 用 " amount " 指向同一字段做放宽变更，应当成功而不是被当成未知字段
        var r = compatibility.apply(new SchemaChangeRequest("admin", " ODS.T ",
                List.of(new ChangeFieldOp("ALTER_TYPE", " AmOuNt ", "bigint"))));
        log.info("用例 ALTER ' AmOuNt ':int->bigint on ' ODS.T ' -> decision={}, 依据={}",
                r.decision(), r.opResults());
        assertEquals(Decision.MERGED, r.decision());
        assertEquals(DataType.BIGINT, store.getEntity("ods.t").getFields().get("amount").getCanonicalType());
    }

    @Test
    @DisplayName("归一：血缘端点名大小写/空白差异不各算一套血缘，重复边幂等不计数")
    void lineageNameWritingVariantsDedup() {
        registration.register(new RegistrationRequest("hive", "A", "TABLE", List.of()));
        registration.register(new RegistrationRequest("hive", "b", "TABLE", List.of()));
        var first = lineage.registerLineage(new LineageRequest("hive", " a ", " B "));
        var again = lineage.registerLineage(new LineageRequest("hive", "A", "b"));
        log.info("用例 ' a '->' B ' 与 A->b -> decisions={},{} 边数={}",
                first.decision(), again.decision(), store.allLineageEdges().size());
        assertEquals(Decision.ACCEPTED, first.decision());
        assertEquals(Decision.NO_CHANGE, again.decision(), "书写差异必须是同一条边");
        assertEquals(1, store.allLineageEdges().size());
    }

    @Test
    @DisplayName("种类不一致：同一实体被声明为 TABLE vs VIEW，KIND_CONFLICT 明确拒绝且不合并")
    void kindMismatchRejectedDistinctFromTypeConflict() {
        var t = registration.register(new RegistrationRequest("hive", "ods.k", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        assertEquals(Decision.ACCEPTED, t.decision());
        // 另一来源声称它是 VIEW（仅大小写不同的实体名，必须指向同一对象再判种类）
        var conflict = registration.register(new RegistrationRequest("kafka", " ODS.K ", "view",
                List.of(new FieldClaim("id", "bigint"))));
        log.info("用例 TABLE vs VIEW（同名异写） -> decision={}, reason={}, 依据={}",
                conflict.decision(), conflict.reason(), conflict.message());
        assertEquals(Decision.REJECTED, conflict.decision());
        assertEquals(RejectReason.KIND_CONFLICT, conflict.reason(),
                "种类不符必须是独立原因，不能混入 TYPE_CONFLICT/INVALID_PAYLOAD");
        // 既有定义不被污染：种类仍是 TABLE、来源只有 hive
        EntityDef entity = store.getEntity("ods.k");
        assertEquals(com.github.highcumontoa.metadatacatalogjava.model.EntityKind.TABLE, entity.getKind());
        assertEquals(1, entity.getSources().size());
        assertTrue(entity.getFields().get("id").getSources().containsKey("hive"));
        assertEquals(1, entity.getFields().get("id").getSources().size(), "种类冲突来源不得挂接到字段");
        // 每条声明都给结论
        assertEquals(1, conflict.fieldResults().size());
        assertEquals(RejectReason.KIND_CONFLICT, conflict.fieldResults().get(0).reason());
    }

    @Test
    @DisplayName("批次内同名字段声明不同类型：FIELD_CONFLICT_IN_PAYLOAD 与同类型重名、既有类型冲突区分")
    void conflictingFieldClaimsInsideBatchRejected() {
        // 新实体批次内：同字段两种类型，互相冲突，整批不落库
        var r = registration.register(new RegistrationRequest("hive", "ods.f", "TABLE", List.of(
                new FieldClaim("c1", "bigint"),
                new FieldClaim("C1", "string"))));
        log.info("用例批次内 c1:bigint/C1:string -> reason={}, 逐条={}", r.reason(), r.fieldResults());
        assertEquals(Decision.REJECTED, r.decision());
        assertEquals(RejectReason.FIELD_CONFLICT_IN_PAYLOAD, r.reason());
        assertEquals(null, store.getEntity("ods.f"), "整批拒绝：实体不得创建");
        var reasons = r.fieldResults().stream().map(x -> x.reason()).toList();
        assertTrue(reasons.contains(RejectReason.FIELD_CONFLICT_IN_PAYLOAD));
        // 同类型重名仍是 DUPLICATE_FIELD_IN_PAYLOAD（区别于互相冲突）
        var dup = registration.register(new RegistrationRequest("hive", "ods.f", "TABLE", List.of(
                new FieldClaim("c1", "bigint"),
                new FieldClaim(" c1 ", "BIGINT"))));
        assertEquals(RejectReason.DUPLICATE_FIELD_IN_PAYLOAD, dup.reason());
    }

    @Test
    @DisplayName("混合批次：合法声明 + 输入级非法声明并存，逐条给结论且整批（含落败台账）完全不落库")
    void mixedBatchWithInvalidInputsLeavesNoTrace() {
        registration.register(new RegistrationRequest("hive", "ods.m", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        var r = registration.register(new RegistrationRequest("mysql", "ods.m", "TABLE", List.of(
                new FieldClaim("new_ok", "string"),        // 合法新增
                new FieldClaim("bad_type", "jsonb"),       // 非法类型
                new FieldClaim(null, "int"),               // 缺字段名
                new FieldClaim("dup", "int"),
                new FieldClaim("DUP", "int"))));           // 批次内同类型重名
        log.info("用例混合批次(含输入级非法) -> decision={}, reason={}, 逐条={}",
                r.decision(), r.reason(),
                r.fieldResults().stream().map(x -> x.fieldName() + ":" + x.reason()).toList());
        assertEquals(Decision.REJECTED, r.decision());
        assertEquals(5, r.fieldResults().size(), "必须逐条给出结论");
        var reasons = r.fieldResults().stream().map(x -> x.reason()).toList();
        assertTrue(reasons.contains(RejectReason.UNKNOWN_TYPE));
        assertTrue(reasons.contains(RejectReason.MISSING_FIELD_NAME));
        assertTrue(reasons.contains(RejectReason.DUPLICATE_FIELD_IN_PAYLOAD));
        EntityDef entity = store.getEntity("ods.m");
        assertTrue(!entity.getFields().containsKey("new_ok"), "合法声明随整批拒绝，不得落库");
        assertTrue(!entity.getSources().contains("mysql"), "含非法输入的批次连来源都不得登记");
        assertTrue(entity.getFields().get("id").getLosingClaims().isEmpty(),
                "含非法输入的批次不得登记任何落败台账，已登记数据不被污染");
    }

    @Test
    @DisplayName("合法批次但与内容裁决冲突：逐条给结论，规范定义不变，落败来源去向登记可追溯")
    void legalBatchLosingAdjudicationRecordsDispositionOnly() {
        registration.register(new RegistrationRequest("hive", "ods.m2", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        var r = registration.register(new RegistrationRequest("mysql", "ods.m2", "TABLE", List.of(
                new FieldClaim("id", "string"),             // 合法输入，但 BIGINT<STRING 裁决落败
                new FieldClaim("note", "text"))));          // 合法新增——随整批拒绝不落库
        log.info("用例合法批次含落败声明 -> decision={}, reason={}, 逐条={}",
                r.decision(), r.reason(),
                r.fieldResults().stream().map(x -> x.fieldName() + ":" + x.reason()).toList());
        assertEquals(Decision.REJECTED, r.decision());
        assertEquals(RejectReason.TYPE_CONFLICT, r.reason());
        assertEquals(2, r.fieldResults().size());
        EntityDef entity = store.getEntity("ods.m2");
        FieldDef id = entity.getFields().get("id");
        assertEquals(DataType.BIGINT, id.getCanonicalType(), "规范类型不因落败声明改变");
        assertTrue(!id.getSources().containsKey("mysql"), "落败来源不挂接到规范定义");
        assertTrue(id.getLosingClaims().containsKey("mysql"), "落败去向必须可追溯");
        assertTrue(!entity.getFields().containsKey("note"), "同批的新字段随整批拒绝不落库");
        assertTrue(!entity.getSources().contains("mysql"), "落败来源不进入实体来源集合");
        // 落败来源随后改声明胜出类型：收敛结果与“一开始就声明 bigint”完全一致
        var retry = registration.register(new RegistrationRequest("mysql", "ods.m2", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        log.info("用例落败来源改声明胜出类型 -> decision={}, 依据={}", retry.decision(),
                retry.fieldResults());
        assertEquals(Decision.MERGED, retry.decision());
        assertTrue(store.getEntity("ods.m2").getFields().get("id").getSources().containsKey("mysql"));
    }

    @Test
    @DisplayName("乱序提交：声明按任意顺序串行提交，最终规范定义与来源去向完全一致")
    void outOfOrderSubmissionsConvergeIdentically() {
        // 两组顺序完全相反的声明，结果必须逐字段、逐来源一致
        LocalMetadataStore storeA = new LocalMetadataStore();
        LocalMetadataStore storeB = new LocalMetadataStore();
        var regA = new RegistrationService(storeA, new TypeDictionary(), new PermissionService());
        var regB = new RegistrationService(storeB, new TypeDictionary(), new PermissionService());

        // storeA：bigint 先到；storeB：string 先到（旧实现会由此产生不同结果）
        regA.register(new RegistrationRequest("src-big", "ods.order", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        regA.register(new RegistrationRequest("src-str", "ods.order", "TABLE",
                List.of(new FieldClaim("id", "string"))));

        regB.register(new RegistrationRequest("src-str", "ods.order", "TABLE",
                List.of(new FieldClaim("id", "string"))));
        regB.register(new RegistrationRequest("src-big", "ods.order", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));

        FieldDef a = storeA.getEntity("ods.order").getFields().get("id");
        FieldDef b = storeB.getEntity("ods.order").getFields().get("id");
        log.info("用例乱序收敛 A(先bigint) canonical={}, winners={}, losers={}",
                a.getCanonicalType(), a.getSources().keySet(), a.getLosingClaims().keySet());
        log.info("用例乱序收敛 B(先string) canonical={}, winners={}, losers={}",
                b.getCanonicalType(), b.getSources().keySet(), b.getLosingClaims().keySet());
        assertEquals(DataType.BIGINT, a.getCanonicalType());
        assertEquals(a.getCanonicalType(), b.getCanonicalType(), "规范类型与到达顺序无关");
        assertEquals(new java.util.TreeSet<>(a.getSources().keySet()),
                new java.util.TreeSet<>(b.getSources().keySet()), "胜出去向与到达顺序无关");
        assertEquals(new java.util.TreeSet<>(a.getLosingClaims().keySet()),
                new java.util.TreeSet<>(b.getLosingClaims().keySet()), "落败去向与到达顺序无关");
        assertEquals(java.util.Set.of("src-big"), new java.util.TreeSet<>(a.getSources().keySet()));
        assertEquals(java.util.Set.of("src-str"), new java.util.TreeSet<>(a.getLosingClaims().keySet()));
        assertEquals(new java.util.TreeSet<>(storeA.getEntity("ods.order").getSources()),
                new java.util.TreeSet<>(storeB.getEntity("ods.order").getSources()),
                "实体级来源集合同样与顺序无关");
    }

    @Test
    @DisplayName("并发收敛（30 轮）：12 线程同时声明同字段两种类型，每轮规范类型与每个来源去向完全一致")
    void concurrentConvergenceRepeatedRounds() throws InterruptedException {
        int threads = 12;
        int rounds = 30;
        java.util.SortedSet<String> expectedWinners = new java.util.TreeSet<>();
        java.util.SortedSet<String> expectedLosers = new java.util.TreeSet<>();
        for (int i = 0; i < threads; i++) {
            (i % 2 == 0 ? expectedWinners : expectedLosers).add("src-" + i);
        }
        // 每轮打乱线程创建顺序，进一步增加调度多样性
        List<Integer> indices = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            indices.add(i);
        }
        for (int round = 0; round < rounds; round++) {
            java.util.Collections.shuffle(indices);
            LocalMetadataStore roundStore = new LocalMetadataStore();
            var roundReg = new RegistrationService(roundStore, new TypeDictionary(), new PermissionService());
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(threads);
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            for (int i : indices) {
                String source = "src-" + i;
                String type = (i % 2 == 0) ? "bigint" : "string";
                pool.submit(() -> {
                    try {
                        start.await();
                        roundReg.register(new RegistrationRequest(source, "  DB.CONCURRENT ", "table",
                                List.of(new FieldClaim(" ID ", " " + type + " "))));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));

            EntityDef entity = roundStore.getEntity("db.concurrent");
            assertNotNull(entity, "第" + round + "轮：实体必须存在（键已归一）");
            FieldDef id = entity.getFields().get("id");
            assertNotNull(id, "第" + round + "轮：字段必须存在（键已归一）");
            assertEquals(DataType.BIGINT, id.getCanonicalType(),
                    "第" + round + "轮：内容裁决结果必须与线程调度无关，恒为 BIGINT");
            assertEquals(expectedWinners, new java.util.TreeSet<>(id.getSources().keySet()),
                    "第" + round + "轮：bigint 来源去向恒为胜出");
            assertEquals(expectedLosers, new java.util.TreeSet<>(id.getLosingClaims().keySet()),
                    "第" + round + "轮：string 来源去向恒为落败");
            assertEquals(expectedWinners, new java.util.TreeSet<>(entity.getSources()),
                    "第" + round + "轮：实体级来源集合恒等于胜出来源集合");
            if (round == 0) {
                log.info("用例并发收敛(30轮/12线程/乱序建池) 第0轮 canonical={} 胜出={} 落败={}（30轮逐项断言一致）",
                        id.getCanonicalType(), id.getSources().keySet(), id.getLosingClaims().keySet());
            }
        }
    }

    @Test
    @DisplayName("并发收敛（20 轮）：同类型声明挤在一批到达时，每个来源恰好登记一次，无重复无丢失")
    void concurrentSameTypeConvergenceRepeatedRounds() throws InterruptedException {
        int threads = 16;
        for (int round = 0; round < 20; round++) {
            LocalMetadataStore roundStore = new LocalMetadataStore();
            var roundReg = new RegistrationService(roundStore, new TypeDictionary(), new PermissionService());
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(threads);
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger merged = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger noChange = new java.util.concurrent.atomic.AtomicInteger();
            for (int i = 0; i < threads; i++) {
                String source = "src-" + i;
                pool.submit(() -> {
                    try {
                        start.await();
                        var r = roundReg.register(new RegistrationRequest(source, "db.same", "TABLE",
                                List.of(new FieldClaim("id", "BIGINT"))));
                        switch (r.decision()) {
                            case ACCEPTED -> accepted.incrementAndGet();
                            case MERGED -> merged.incrementAndGet();
                            case NO_CHANGE -> noChange.incrementAndGet();
                            default -> { }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
            FieldDef id = roundStore.getEntity("db.same").getFields().get("id");
            assertEquals(1, accepted.get(), "第" + round + "轮：恰好一个请求建实体");
            assertEquals(0, noChange.get(), "第" + round + "轮：不同来源的相同声明应合并而非幂等");
            assertEquals(threads - 1, merged.get());
            assertEquals(threads, id.getSources().size(), "第" + round + "轮：来源不重不漏");
            if (round == 0) {
                log.info("用例并发同类型收敛(20轮/16线程) 第0轮 ACCEPTED={} MERGED={} 字段来源数={}",
                        accepted.get(), merged.get(), id.getSources().size());
            }
        }
    }

    @Test
    @DisplayName("写保护：实体废弃后，登记/变更/血缘/再废弃/移除五个写入口全部 ENTITY_DEPRECATED（admin 亦然）")
    void deprecatedEntityRejectsEveryWriteEntry() {
        registration.register(new RegistrationRequest("hive", "ods.d", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        registration.register(new RegistrationRequest("hive", "ods.e", "TABLE", List.of()));
        lineage.registerLineage(new LineageRequest("hive", "ods.e", "ods.d"));
        assertEquals(RejectReason.NONE, admin.deprecate("admin", "ods.d"));

        // 1. 登记
        var reg = registration.register(new RegistrationRequest("mysql", " ODS.D ", "TABLE",
                List.of(new FieldClaim("x", "string"))));
        log.info("用例废弃后登记 -> decision={}, reason={}", reg.decision(), reg.reason());
        assertEquals(Decision.REJECTED, reg.decision());
        assertEquals(RejectReason.ENTITY_DEPRECATED, reg.reason());

        // 2. 字段变更（即使是 admin）
        var change = compatibility.apply(new SchemaChangeRequest("admin", "ods.d",
                List.of(new ChangeFieldOp("ADD", "x", "string"))));
        log.info("用例废弃后变更(admin) -> reason={}", change.reason());
        assertEquals(RejectReason.ENTITY_DEPRECATED, change.reason());

        // 3. 血缘登记（端点含已废弃实体）
        registration.register(new RegistrationRequest("hive", "ods.f2", "TABLE", List.of()));
        var lin = lineage.registerLineage(new LineageRequest("admin", "ods.f2", "ods.d"));
        log.info("用例废弃后血缘(下游已废弃) -> reason={}", lin.reason());
        assertEquals(RejectReason.ENTITY_DEPRECATED, lin.reason());
        var lin2 = lineage.registerLineage(new LineageRequest("admin", "ods.d", "ods.f2"));
        assertEquals(RejectReason.ENTITY_DEPRECATED, lin2.reason());

        // 4. 再废弃（幂等状态也要以 ENTITY_DEPRECATED 拒绝，不能报成功）
        var dep = admin.deprecate("admin", "ods.d");
        log.info("用例废弃后再废弃(admin) -> reason={}", dep);
        assertEquals(RejectReason.ENTITY_DEPRECATED, dep);

        // 5. 移除
        var rem = admin.remove("admin", "ods.d");
        log.info("用例废弃后移除(admin) -> reason={}", rem);
        assertEquals(RejectReason.ENTITY_DEPRECATED, rem);

        // 实体仍然存在、血缘仍然存在（没有任何写动作穿透）
        assertNotNull(store.getEntity("ods.d"));
        assertEquals(1, store.allLineageEdges().size());
        assertTrue(!store.getEntity("ods.d").getFields().containsKey("x"));
    }

    @Test
    @DisplayName("写保护：只读（白名单）状态下，登记/变更/血缘/废弃/移除对未授权者全部 PERMISSION_DENIED，授权者放行")
    void readOnlyEntityRejectsUnauthorizedOnEveryWriteEntry() {
        registration.register(new RegistrationRequest("hive", "ods.p", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        registration.register(new RegistrationRequest("hive", "ods.q", "TABLE", List.of()));
        // 授权一次即进入白名单模式：仅 bob 与 admin 可写
        permissions.grant(" ODS.P ", "bob");

        // 未授权者 carol：五个入口全部 PERMISSION_DENIED
        var reg = registration.register(new RegistrationRequest("carol", "ods.p", "TABLE",
                List.of(new FieldClaim("x", "string"))));
        assertEquals(RejectReason.PERMISSION_DENIED, reg.reason());
        var change = compatibility.apply(new SchemaChangeRequest("carol", "ods.p",
                List.of(new ChangeFieldOp("ADD", "x", "string"))));
        assertEquals(RejectReason.PERMISSION_DENIED, change.reason());
        var lin = lineage.registerLineage(new LineageRequest("carol", "ods.q", "ods.p"));
        assertEquals(RejectReason.PERMISSION_DENIED, lin.reason());
        assertEquals(RejectReason.PERMISSION_DENIED, admin.deprecate("carol", "ods.p"));
        assertEquals(RejectReason.PERMISSION_DENIED, admin.remove("carol", "ods.p"));
        log.info("用例只读状态下 carol 五入口 -> 全部 PERMISSION_DENIED");

        // 白名单大小写/空白归一对授权同样生效：" BOB " 不能被当成另一个操作者
        var changeBob = compatibility.apply(new SchemaChangeRequest(" BOB ", " ods.p ",
                List.of(new ChangeFieldOp("ADD", "note", "text"))));
        log.info("用例白名单操作者 ' BOB ' 归一后 -> decision={}", changeBob.decision());
        assertEquals(Decision.MERGED, changeBob.decision());
        // admin 超级权限始终放行
        assertEquals(RejectReason.NONE, admin.deprecate("admin", "ods.p"));
        // 废弃后白名单内的 bob 也必须被 ENTITY_DEPRECATED 挡住（废弃优先于权限）
        var afterDep = compatibility.apply(new SchemaChangeRequest("bob", "ods.p",
                List.of(new ChangeFieldOp("ADD", "y", "string"))));
        log.info("用例废弃后白名单 bob -> reason={}（废弃优先）", afterDep.reason());
        assertEquals(RejectReason.ENTITY_DEPRECATED, afterDep.reason());
    }

    @Test
    @DisplayName("内容裁决可解释：字典序更强的声明后到时翻转规范类型，旧胜出来源整体转入落败，依据写入结论")
    void adjudicationFlipMovesPriorWinnersToLosers() {
        registration.register(new RegistrationRequest("s1", "ods.flip", "TABLE",
                List.of(new FieldClaim("id", "string"))));
        registration.register(new RegistrationRequest("s2", "ods.flip", "TABLE",
                List.of(new FieldClaim("id", "string"))));
        FieldDef before = store.getEntity("ods.flip").getFields().get("id");
        assertEquals(DataType.STRING, before.getCanonicalType());
        assertEquals(java.util.Set.of("s1", "s2"), new java.util.TreeSet<>(before.getSources().keySet()));

        // BIGINT 字典序优先于 STRING：即使后到，也按内容裁决翻转，而不是 TYPE_CONFLICT 拒绝覆盖
        var flip = registration.register(new RegistrationRequest("s3", "ods.flip", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        log.info("用例 STRING 已登记两来源后 BIGINT 到达 -> decision={}, 依据={}",
                flip.decision(), flip.fieldResults().get(0).detail());
        assertEquals(Decision.MERGED, flip.decision());
        FieldDef after = store.getEntity("ods.flip").getFields().get("id");
        assertEquals(DataType.BIGINT, after.getCanonicalType());
        assertEquals(java.util.Set.of("s3"), new java.util.TreeSet<>(after.getSources().keySet()),
                "新胜出来源集合");
        assertEquals(java.util.Set.of("s1", "s2"), new java.util.TreeSet<>(after.getLosingClaims().keySet()),
                "旧胜出来源整体转入落败集合，去向可追溯");
        assertTrue(flip.fieldResults().get(0).detail().contains("字典序"),
                "逐条结论必须写明裁决依据");
        assertEquals(java.util.Set.of("s3"), new java.util.TreeSet<>(store.getEntity("ods.flip").getSources()),
                "实体来源集合按收敛结果只保留胜出者");
        // 被翻转迁出的来源必须在审计轨迹中留下独立的 TYPE_CONFLICT 记录（去向变化可追溯）
        List<com.github.highcumontoa.metadatacatalogjava.model.AuditRecord> flipAudit =
                store.auditTrail("ods.flip").stream()
                        .filter(a -> a.reason() == RejectReason.TYPE_CONFLICT
                                && (a.operator().equals("s1") || a.operator().equals("s2")))
                        .toList();
        log.info("用例翻转迁移审计条数={}（s1/s2 各一条去向迁移记录）", flipAudit.size());
        assertEquals(2, flipAudit.size());

        // 旧来源随后把声明改为 bigint：收敛结果与三者一开始就声明 bigint 完全一致
        var fix1 = registration.register(new RegistrationRequest("s1", "ods.flip", "TABLE",
                List.of(new FieldClaim("ID", "BIGINT"))));
        var fix2 = registration.register(new RegistrationRequest("s2", "ods.flip", "TABLE",
                List.of(new FieldClaim(" id ", " bigint "))));
        assertEquals(Decision.MERGED, fix1.decision());
        assertEquals(Decision.MERGED, fix2.decision());
        FieldDef healed = store.getEntity("ods.flip").getFields().get("id");
        assertEquals(DataType.BIGINT, healed.getCanonicalType());
        assertEquals(java.util.Set.of("s1", "s2", "s3"),
                new java.util.TreeSet<>(healed.getSources().keySet()));
        assertTrue(healed.getLosingClaims().isEmpty(), "全部来源一致后落败台账清空");
        log.info("用例旧来源改声明后收敛 -> winners={}, losers={}",
                healed.getSources().keySet(), healed.getLosingClaims().keySet());
    }

    @Test
    @DisplayName("并发归一（15 轮）：同一条边的不同书写并发登记，边始终只有一条，恰一个 ACCEPTED 其余 NO_CHANGE")
    void concurrentLineageWritingVariantsConverge() throws InterruptedException {
        int threads = 12;
        String[] upVariants = {"a", " A ", "a", " A", "a ", "A", " a ", "a", "A", " a", "a ", " A "};
        String[] downVariants = {"b", " B ", "b", " B", "b ", "B", " b ", "b", "B", " b", "b ", " B "};
        for (int round = 0; round < 15; round++) {
            LocalMetadataStore roundStore = new LocalMetadataStore();
            var perms = new PermissionService();
            var roundReg = new RegistrationService(roundStore, new TypeDictionary(), perms);
            var roundLineage = new LineageService(roundStore, perms);
            roundReg.register(new RegistrationRequest("hive", "a", "TABLE", List.of()));
            roundReg.register(new RegistrationRequest("hive", "b", "TABLE", List.of()));

            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(threads);
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger noChange = new java.util.concurrent.atomic.AtomicInteger();
            for (int i = 0; i < threads; i++) {
                int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        var r = roundLineage.registerLineage(
                                new LineageRequest("hive", upVariants[idx], downVariants[idx]));
                        if (r.decision() == Decision.ACCEPTED) {
                            accepted.incrementAndGet();
                        } else if (r.decision() == Decision.NO_CHANGE) {
                            noChange.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, roundStore.allLineageEdges().size(), "第" + round + "轮：书写差异不得各算一套血缘");
            assertEquals(1, accepted.get(), "第" + round + "轮：恰好一个 ACCEPTED");
            assertEquals(threads - 1, noChange.get(), "第" + round + "轮：其余全部 NO_CHANGE");
            assertEquals("a", roundStore.allLineageEdges().get(0).upstream());
            assertEquals("b", roundStore.allLineageEdges().get(0).downstream());
            if (round == 0) {
                log.info("用例血缘并发归一(15轮/12线程不同书写) 第0轮 ACCEPTED={} NO_CHANGE={} 唯一边={}->{}",
                        accepted.get(), noChange.get(),
                        roundStore.allLineageEdges().get(0).upstream(),
                        roundStore.allLineageEdges().get(0).downstream());
            }
        }
    }

    @Test
    @DisplayName("拒绝分类：不存在的实体(UNKNOWN_ENTITY) 与 实体存在但字段不存在(UNKNOWN_FIELD) 在各入口明确区分")
    void unknownEntityAndUnknownFieldAreDistinct() {
        registration.register(new RegistrationRequest("hive", "ods.u", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        registration.register(new RegistrationRequest("hive", "ods.peer", "TABLE", List.of()));

        // 变更：实体不存在
        var noEntity = compatibility.apply(new SchemaChangeRequest("admin", " ODS.GHOST ",
                List.of(new ChangeFieldOp("ADD", "x", "string"))));
        log.info("用例变更不存在实体 ' ODS.GHOST ' -> reason={}", noEntity.reason());
        assertEquals(RejectReason.UNKNOWN_ENTITY, noEntity.reason());

        // 变更：实体存在、字段不存在（ALTER_TYPE）
        var noFieldAlter = compatibility.apply(new SchemaChangeRequest("admin", "ods.u",
                List.of(new ChangeFieldOp("ALTER_TYPE", " GHOST_FIELD ", "bigint"))));
        log.info("用例 ALTER 不存在字段 -> reason={}, 依据={}", noFieldAlter.reason(),
                noFieldAlter.opResults().get(0).detail());
        assertEquals(RejectReason.UNKNOWN_FIELD, noFieldAlter.opResults().get(0).reason());

        // 变更：实体存在、字段不存在（DROP）
        var noFieldDrop = compatibility.apply(new SchemaChangeRequest("admin", "ods.u",
                List.of(new ChangeFieldOp("DROP", "ghost_field", null))));
        assertEquals(RejectReason.UNKNOWN_FIELD, noFieldDrop.opResults().get(0).reason());

        // 同一批次中 UNKNOWN_ENTITY 与 UNKNOWN_FIELD 不会混淆：实体存在时绝不应给 UNKNOWN_ENTITY
        var mixed = compatibility.apply(new SchemaChangeRequest("admin", "ods.u",
                List.of(new ChangeFieldOp("ALTER_TYPE", "id", "decimal"),
                        new ChangeFieldOp("ALTER_TYPE", "nope", "decimal"))));
        log.info("用例同批 存在字段放宽 + 不存在字段 -> reasons={}",
                mixed.opResults().stream().map(x -> x.fieldName() + ":" + x.reason()).toList());
        assertEquals(Decision.REJECTED, mixed.decision());
        assertEquals(RejectReason.UNKNOWN_FIELD, mixed.reason(), "主因必须是字段不存在而非实体不存在");

        // 血缘端点不存在
        var noLin = lineage.registerLineage(new LineageRequest("hive", "ods.u", "ods.ghost"));
        log.info("用例血缘端点不存在 -> reason={}", noLin.reason());
        assertEquals(RejectReason.UNKNOWN_ENTITY, noLin.reason());

        // 废弃/移除不存在的实体
        assertEquals(RejectReason.UNKNOWN_ENTITY, admin.deprecate("admin", "ods.ghost"));
        assertEquals(RejectReason.UNKNOWN_ENTITY, admin.remove("admin", "ods.ghost"));

        // 登记到不存在的“实体种类语境”不适用（登记本身创建实体），但移除幽灵实体必须留下 UNKNOWN_ENTITY 审计
        List<com.github.highcumontoa.metadatacatalogjava.model.AuditRecord> ghostAudit =
                store.allAudit().stream()
                        .filter(a -> a.targetEntity() != null && a.targetEntity().equals("ods.ghost"))
                        .toList();
        assertTrue(ghostAudit.stream().allMatch(a -> a.reason() == RejectReason.UNKNOWN_ENTITY));
        assertTrue(ghostAudit.size() >= 3);
    }
}
