package com.github.highcumontoa.metadatacatalogjava.tests;

import com.github.highcumontoa.metadatacatalogjava.dto.ChangeFieldOp;
import com.github.highcumontoa.metadatacatalogjava.dto.FieldClaim;
import com.github.highcumontoa.metadatacatalogjava.dto.LineageRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.RegistrationRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.RegistrationResult;
import com.github.highcumontoa.metadatacatalogjava.dto.SchemaChangeRequest;
import com.github.highcumontoa.metadatacatalogjava.model.DataType;
import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.EntityDef;
import com.github.highcumontoa.metadatacatalogjava.model.EntityKind;
import com.github.highcumontoa.metadatacatalogjava.model.FieldDef;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归一收敛与写入口一致性测试：
 * 书写差异归一、乱序 / 并发声明的收敛一致性、种类不一致拒绝、混合批次整体不落库。
 * 每次输入与判定依据均通过日志打印（服务层 [REGISTER]/[CHANGE] 等前缀 + 用例日志）。
 */
class NormalizationConvergenceTest {

    private static final Logger log = LoggerFactory.getLogger(NormalizationConvergenceTest.class);

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
        var arbitrator = new TypeArbitrator();
        permissions = new PermissionService();
        registration = new RegistrationService(store, types, permissions, arbitrator);
        compatibility = new CompatibilityService(store, types, permissions);
        lineage = new LineageService(store, permissions);
        admin = new EntityAdminService(store, permissions);
    }

    @Test
    @DisplayName("书写差异归一：大小写 / 首尾空白差异的实体名与字段名在所有写入口视为同一对象")
    void spellingVariantsNormalizeToSameObject() {
        // 登记：实体名带尾部空白与大小写差异，字段名带空白与大小写差异
        var r1 = registration.register(new RegistrationRequest("hive", " ODS.User ", "TABLE",
                List.of(new FieldClaim(" ID ", "bigint"))));
        log.info("用例输入 ' ODS.User '/' ID ' -> decision={}, 归一名={}", r1.decision(), r1.qualifiedName());
        assertEquals(Decision.ACCEPTED, r1.decision());
        assertEquals("ods.user", r1.qualifiedName());

        // 另一来源用全小写登记同一实体同一字段（类型归一后一致）-> 合并到同一份定义
        var r2 = registration.register(new RegistrationRequest("mysql", "ods.user", "table",
                List.of(new FieldClaim("id", "long"))));
        log.info("用例输入 'ods.user'/'id' -> decision={}", r2.decision());
        assertEquals(Decision.MERGED, r2.decision());

        assertEquals(1, store.allEntities().size(), "书写差异不得各自建一份实体");
        EntityDef entity = store.getEntity("ods.user");
        assertNotNull(entity);
        assertEquals(2, entity.getSources().size());
        assertEquals(1, entity.getFields().size(), "书写差异不得各自建一份字段");
        assertEquals(2, entity.getFields().get("id").getSources().size());

        // 字段变更入口：大小写变体定位到同一实体同一字段
        var change = compatibility.apply(new SchemaChangeRequest("alice", "ODS.USER",
                List.of(new ChangeFieldOp("ALTER_TYPE", " Id", "decimal"))));
        log.info("用例变更入口 'ODS.USER'/' Id' -> decision={}", change.decision());
        assertEquals(Decision.MERGED, change.decision());
        assertEquals(DataType.DECIMAL, store.getEntity("ods.user").getFields().get("id").getCanonicalType());

        // 血缘入口：大小写变体指向同一批实体，不会各算一套血缘
        registration.register(new RegistrationRequest("hive", "ODS.Order", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        var lin = lineage.registerLineage(new LineageRequest("hive", " ods.user", "ODS.ORDER "));
        log.info("用例血缘入口 ' ods.user'->'ODS.ORDER ' -> decision={}", lin.decision());
        assertEquals(Decision.ACCEPTED, lin.decision());
        assertEquals(1, store.allLineageEdges().size());
        // 归一后的同一条边重复登记 -> NO_CHANGE，不重复计数
        var dup = lineage.registerLineage(new LineageRequest("hive", "ODS.USER", "ods.order"));
        assertEquals(Decision.NO_CHANGE, dup.decision());
        assertEquals(1, store.allLineageEdges().size());

        // 废弃入口：大小写变体废弃同一实体
        assertEquals(RejectReason.NONE, admin.deprecate("admin", " Ods.User "));
        assertTrue(store.getEntity("ods.user").isDeprecated());
        log.info("用例书写差异在登记/变更/血缘/废弃四个入口均归一到同一对象，判定通过");
    }

    @Test
    @DisplayName("裁决可解释：高优先级类型取代既有规范，既有来源转入拒绝台账，依据写入结论")
    void arbitrationIsDeterministicAndExplainable() {
        registration.register(new RegistrationRequest("s1", "db.arb", "TABLE",
                List.of(new FieldClaim("v", "int"))));
        var r2 = registration.register(new RegistrationRequest("s2", "db.arb", "TABLE",
                List.of(new FieldClaim("v", "bigint"))));
        log.info("用例 s1:int 后 s2:bigint -> decision={}, 依据={}",
                r2.decision(), r2.fieldResults().get(0).detail());
        assertEquals(Decision.MERGED, r2.decision());
        assertTrue(r2.fieldResults().get(0).detail().contains("优先级"), "结论必须写明裁决依据");

        FieldDef v = store.getEntity("db.arb").getFields().get("v");
        assertEquals(DataType.BIGINT, v.getCanonicalType(), "BIGINT 优先级高于 INTEGER，与到达顺序无关");
        assertTrue(v.getSources().containsKey("s2"));
        assertEquals("int", v.getRejectedClaims().get("s1"), "被取代来源的去向必须可查询");

        // 反向顺序：s3 先声明 bigint，s4 后声明 int（低优先级）-> TYPE_CONFLICT 拒绝
        registration.register(new RegistrationRequest("s3", "db.arb2", "TABLE",
                List.of(new FieldClaim("v", "bigint"))));
        var r4 = registration.register(new RegistrationRequest("s4", "db.arb2", "TABLE",
                List.of(new FieldClaim("v", "int"))));
        log.info("用例 s3:bigint 后 s4:int -> decision={}, reason={}, 依据={}",
                r4.decision(), r4.reason(), r4.fieldResults().get(0).detail());
        assertEquals(Decision.REJECTED, r4.decision());
        assertEquals(RejectReason.TYPE_CONFLICT, r4.reason());
        FieldDef v2 = store.getEntity("db.arb2").getFields().get("v");
        assertEquals(DataType.BIGINT, v2.getCanonicalType());
        assertEquals("int", v2.getRejectedClaims().get("s4"));
        log.info("用例两种顺序下规范类型与来源去向一致（BIGINT 生效，int 来源入台账）");
    }

    @Test
    @DisplayName("乱序收敛：同一组声明打乱顺序提交 10 轮，最终规范定义与每个来源去向完全一致")
    void shuffledOrderConvergesToSameFinalState() {
        // 声明集合：四种类型，优先级 DECIMAL > BIGINT > INTEGER > STRING
        List<String[]> claims = List.of(
                new String[]{"src-int", "int"},
                new String[]{"src-bigint", "bigint"},
                new String[]{"src-string", "string"},
                new String[]{"src-decimal", "decimal"});
        for (int round = 0; round < 10; round++) {
            LocalMetadataStore roundStore = new LocalMetadataStore();
            RegistrationService roundReg = new RegistrationService(roundStore, new TypeDictionary(),
                    new PermissionService(), new TypeArbitrator());
            List<String[]> shuffled = new ArrayList<>(claims);
            Collections.shuffle(shuffled, new Random(round * 31L + 7));
            log.info("第{}轮提交顺序={}", round,
                    shuffled.stream().map(c -> c[0] + ":" + c[1]).toList());
            for (String[] c : shuffled) {
                roundReg.register(new RegistrationRequest(c[0], "db.shuffle", "TABLE",
                        List.of(new FieldClaim("v", c[1]))));
            }
            FieldDef v = roundStore.getEntity("db.shuffle").getFields().get("v");
            assertEquals(DataType.DECIMAL, v.getCanonicalType(),
                    "第" + round + "轮：规范类型必须收敛到优先级最高的 DECIMAL，与顺序无关");
            assertEquals(java.util.Set.of("src-decimal"), v.getSources().keySet(),
                    "第" + round + "轮：只有最高优先级来源生效");
            assertEquals(java.util.Set.of("src-int", "src-bigint", "src-string"),
                    v.getRejectedClaims().keySet(),
                    "第" + round + "轮：其余来源的去向必须都是被拒绝，与顺序无关");
        }
        log.info("用例 10 轮乱序提交最终状态完全一致：DECIMAL 生效，int/bigint/string 来源入拒绝台账");
    }

    @Test
    @DisplayName("并发收敛：书写差异 + 冲突类型混合并发 20 轮，最终状态与线程调度无关")
    void concurrentMixedClaimsConvergeDeterministically() throws InterruptedException {
        int threads = 8;
        for (int round = 0; round < 20; round++) {
            LocalMetadataStore roundStore = new LocalMetadataStore();
            RegistrationService roundReg = new RegistrationService(roundStore, new TypeDictionary(),
                    new PermissionService(), new TypeArbitrator());
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            // 实体名带大小写 / 空白变体；src-0..3 声明 decimal，src-4..7 声明 bigint
            String[] nameVariants = {"db.conv", "DB.CONV", " db.Conv ", "Db.conv"};
            for (int i = 0; i < threads; i++) {
                String source = "src-" + i;
                String qn = nameVariants[i % nameVariants.length];
                String type = (i < 4) ? "decimal" : "bigint";
                pool.submit(() -> {
                    try {
                        start.await();
                        roundReg.register(new RegistrationRequest(source, qn, "TABLE",
                                List.of(new FieldClaim("v", type))));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

            assertEquals(1, roundStore.allEntities().size(),
                    "第" + round + "轮：书写变体必须归一为同一实体");
            FieldDef v = roundStore.getEntity("db.conv").getFields().get("v");
            assertEquals(DataType.DECIMAL, v.getCanonicalType(),
                    "第" + round + "轮：规范类型收敛到 DECIMAL，与线程调度无关");
            assertEquals(java.util.Set.of("src-0", "src-1", "src-2", "src-3"), v.getSources().keySet(),
                    "第" + round + "轮：decimal 来源全部生效");
            assertEquals(java.util.Set.of("src-4", "src-5", "src-6", "src-7"),
                    v.getRejectedClaims().keySet(),
                    "第" + round + "轮：bigint 来源去向全部是被拒绝");
            if (round == 0) {
                log.info("用例并发混合声明 生效来源={} 拒绝台账={} 规范类型={}",
                        v.getSources().keySet(), v.getRejectedClaims().keySet(), v.getCanonicalType());
            }
        }
        log.info("用例 20 轮并发最终状态完全一致，结论与线程调度无关");
    }

    @Test
    @DisplayName("种类不一致：同名实体不同种类以 KIND_CONFLICT 拒绝，与类型冲突 / 输入非法明确区分")
    void kindConflictRejectedDistinctly() {
        registration.register(new RegistrationRequest("hive", "db.k", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        var r = registration.register(new RegistrationRequest("mysql", "DB.K ", "VIEW",
                List.of(new FieldClaim("id", "bigint"), new FieldClaim("extra", "string"))));
        log.info("用例 TABLE 实体收到 VIEW 声明 -> decision={}, reason={}, 依据={}",
                r.decision(), r.reason(), r.message());
        assertEquals(Decision.REJECTED, r.decision());
        assertEquals(RejectReason.KIND_CONFLICT, r.reason());
        // 三类原因可区分
        assertNotEquals(RejectReason.TYPE_CONFLICT, r.reason());
        assertNotEquals(RejectReason.INVALID_PAYLOAD, r.reason());
        // 不悄悄合成：种类不变、批次内新字段不落库、来源不记录
        EntityDef entity = store.getEntity("db.k");
        assertEquals(EntityKind.TABLE, entity.getKind());
        assertFalse(entity.getFields().containsKey("extra"), "种类不符批次不得落库任何字段");
        assertFalse(entity.getSources().contains("mysql"), "种类不符来源不得并入实体来源");
    }

    @Test
    @DisplayName("混合批次：合法 + 冲突声明同批时整批不落库，已登记数据不被污染")
    void mixedBatchRejectedAtomicallyWithoutPollution() {
        registration.register(new RegistrationRequest("hive", "db.mix", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        // 批次内含一个合法新字段与一个低优先级冲突字段
        var r = registration.register(new RegistrationRequest("mysql", "db.mix", "TABLE",
                List.of(new FieldClaim("note", "string"), new FieldClaim("id", "int"))));
        log.info("用例混合批次 note:string(合法)+id:int(冲突) -> decision={}, 逐字段={}",
                r.decision(), r.fieldResults());
        assertEquals(Decision.REJECTED, r.decision());
        assertEquals(RejectReason.TYPE_CONFLICT, r.reason());
        // 逐条给出结论：合法字段与冲突字段各有判定
        assertEquals(2, r.fieldResults().size());
        EntityDef entity = store.getEntity("db.mix");
        assertFalse(entity.getFields().containsKey("note"), "整批拒绝，合法字段同样不得落库");
        assertEquals(DataType.BIGINT, entity.getFields().get("id").getCanonicalType(),
                "既有规范定义不得被污染");
        assertFalse(entity.getSources().contains("mysql"), "整批拒绝，来源不得并入");
        assertEquals("int", entity.getFields().get("id").getRejectedClaims().get("mysql"),
                "落败来源去向记录在拒绝台账（仅交代去向，不是规范数据）");
        // 后续单独提交合法字段可正常落库，证明此前批次没有留下半截状态
        var retry = registration.register(new RegistrationRequest("mysql", "db.mix", "TABLE",
                List.of(new FieldClaim("note", "string"))));
        assertEquals(Decision.MERGED, retry.decision());
        log.info("用例混合批次整体不落库，既有数据未污染，后续合法批次正常合并");
    }
}
