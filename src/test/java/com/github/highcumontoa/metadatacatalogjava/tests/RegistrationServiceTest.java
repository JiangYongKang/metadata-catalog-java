package com.github.highcumontoa.metadatacatalogjava.tests;

import com.github.highcumontoa.metadatacatalogjava.dto.FieldClaim;
import com.github.highcumontoa.metadatacatalogjava.dto.FieldResult;
import com.github.highcumontoa.metadatacatalogjava.dto.RegistrationRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.RegistrationResult;
import com.github.highcumontoa.metadatacatalogjava.model.DataType;
import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.EntityDef;
import com.github.highcumontoa.metadatacatalogjava.model.FieldDef;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登记服务单测：归一合并、逐字段拒绝原因区分、幂等与并发确定性。
 * 日志中打印输入与判定依据（见 [REGISTER] 前缀日志）。
 */
class RegistrationServiceTest {

    private static final Logger log = LoggerFactory.getLogger(RegistrationServiceTest.class);

    private LocalMetadataStore store;
    private RegistrationService registration;

    @BeforeEach
    void setUp() {
        store = new LocalMetadataStore();
        registration = new RegistrationService(store, new TypeDictionary(), new PermissionService());
    }

    @Test
    @DisplayName("不同来源的原始类型归一到统一模型并合并来源")
    void normalizeAndMerge() {
        var r1 = registration.register(new RegistrationRequest("hive", "db.t1", "TABLE",
                List.of(new FieldClaim("id", "BIGINT"), new FieldClaim("name", "varchar"))));
        log.info("用例输入 hive/BIGINT+varchar -> decision={}, fields={}", r1.decision(), r1.fieldResults());
        assertEquals(Decision.ACCEPTED, r1.decision());

        var r2 = registration.register(new RegistrationRequest("mysql", "db.t1", "table",
                List.of(new FieldClaim("id", "long"), new FieldClaim("name", "text"))));
        log.info("用例输入 mysql/long+text -> decision={}, fields={}", r2.decision(), r2.fieldResults());
        assertEquals(Decision.MERGED, r2.decision());

        EntityDef entity = store.getEntity("db.t1");
        FieldDef id = entity.getFields().get("id");
        assertEquals(DataType.BIGINT, id.getCanonicalType());
        assertEquals(2, id.getSources().size());
        log.info("用例判定依据：id 字段来源集合={} 规范类型={}", id.getSources(), id.getCanonicalType());
    }

    @Test
    @DisplayName("重复登记相同声明：NO_CHANGE 且不重复计数")
    void duplicateIsNoChange() {
        var req = new RegistrationRequest("hive", "db.t2", "TABLE",
                List.of(new FieldClaim("id", "bigint")));
        var first = registration.register(req);
        var second = registration.register(req);
        var third = registration.register(req);
        log.info("用例连续三次相同输入 decision序列={},{},{}", first.decision(), second.decision(), third.decision());
        assertEquals(Decision.ACCEPTED, first.decision());
        assertEquals(Decision.NO_CHANGE, second.decision());
        assertEquals(Decision.NO_CHANGE, third.decision());
        assertEquals(1, store.getEntity("db.t2").getFields().get("id").getSources().size());
    }

    @Test
    @DisplayName("非法类型 / 缺字段名 / 批次内重复字段：原因可区分且整批不落库")
    void invalidFieldsRejectedWithDistinctReasons() {
        var req = new RegistrationRequest("hive", "db.t3", "TABLE", List.of(
                new FieldClaim("a", "weirdtype"),
                new FieldClaim(null, "bigint"),
                new FieldClaim("b", "bigint"),
                new FieldClaim("b", "BIGINT ")));
        var result = registration.register(req);
        log.info("用例输入4类问题字段 -> decision={}, 逐字段结论={}", result.decision(), result.fieldResults());
        assertEquals(Decision.REJECTED, result.decision());
        var reasons = result.fieldResults().stream().map(FieldResult::reason).toList();
        log.info("用例逐条拒绝原因（按原因可区分）={}", reasons);
        assertTrue(reasons.contains(RejectReason.UNKNOWN_TYPE));
        assertTrue(reasons.contains(RejectReason.MISSING_FIELD_NAME));
        assertTrue(reasons.contains(RejectReason.DUPLICATE_FIELD_IN_PAYLOAD));
        // 整批拒绝：实体不得被创建
        assertEquals(null, store.getEntity("db.t3"));
    }

    @Test
    @DisplayName("同名字段类型冲突：TYPE_CONFLICT 拒绝且不覆盖既有定义")
    void typeConflictRejected() {
        registration.register(new RegistrationRequest("hive", "db.t4", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        var conflict = registration.register(new RegistrationRequest("mysql", "db.t4", "TABLE",
                List.of(new FieldClaim("id", "string"))));
        log.info("用例输入冲突类型 bigint->string -> decision={}, reason={}, 依据={}",
                conflict.decision(), conflict.reason(), conflict.message());
        assertEquals(Decision.REJECTED, conflict.decision());
        assertEquals(RejectReason.TYPE_CONFLICT, conflict.reason());
        assertEquals(DataType.BIGINT, store.getEntity("db.t4").getFields().get("id").getCanonicalType());
        assertEquals(1, store.getEntity("db.t4").getFields().get("id").getSources().size());
    }

    @Test
    @DisplayName("缺少必填项与非法 kind：INVALID_PAYLOAD 原因可区分")
    void invalidPayload() {
        var noSource = registration.register(new RegistrationRequest(" ", "db.t5", "TABLE", List.of()));
        var badKind = registration.register(new RegistrationRequest("hive", "db.t5", "TOPIC", List.of()));
        log.info("用例输入空source / 非法kind -> reason={}, {}", noSource.reason(), badKind.reason());
        assertEquals(RejectReason.INVALID_PAYLOAD, noSource.reason());
        assertEquals(RejectReason.INVALID_PAYLOAD, badKind.reason());
    }

    @Test
    @DisplayName("并发登记：相同声明多线程提交，最终状态确定且无丢失更新")
    void concurrentIdenticalRegistrationsAreDeterministic() throws InterruptedException {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger merged = new AtomicInteger();
        AtomicInteger noChange = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            String source = "src-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    RegistrationResult r = registration.register(new RegistrationRequest(source, "db.concurrent",
                            "TABLE", List.of(new FieldClaim("id", "bigint"))));
                    switch (r.decision()) {
                        case ACCEPTED -> accepted.incrementAndGet();
                        case MERGED -> merged.incrementAndGet();
                        case NO_CHANGE -> noChange.incrementAndGet();
                        default -> rejected.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        EntityDef entity = store.getEntity("db.concurrent");
        assertNotNull(entity);
        assertEquals(threads, entity.getSources().size(), "每个来源都必须被记录，不得丢失");
        assertEquals(threads, entity.getFields().get("id").getSources().size());
        log.info("用例并发相同声明 线程数={} ACCEPTED={} MERGED={} NO_CHANGE={} REJECTED={} 最终来源数={}",
                threads, accepted.get(), merged.get(), noChange.get(), rejected.get(),
                entity.getSources().size());
        assertEquals(1, accepted.get(), "恰好一个线程创建实体");
        assertEquals(0, rejected.get(), "相同声明不得有任何拒绝");
        assertEquals(threads, accepted.get() + merged.get() + noChange.get());
    }

    @Test
    @DisplayName("并发登记：同字段冲突类型，按内容裁决收敛——规范类型与每个来源去向与调度无关，结论稳定")
    void concurrentConflictingRegistrationsSingleCanonical() throws InterruptedException {
        int threads = 12;
        java.util.Set<String> expectedWinners = new java.util.TreeSet<>();
        java.util.Set<String> expectedLosers = new java.util.TreeSet<>();
        for (int i = 0; i < threads; i++) {
            (i % 2 == 0 ? expectedWinners : expectedLosers).add("src-" + i);
        }
        for (int round = 0; round < 20; round++) {
            LocalMetadataStore roundStore = new LocalMetadataStore();
            RegistrationService roundReg = new RegistrationService(roundStore, new TypeDictionary(),
                    new PermissionService());
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger conflict = new AtomicInteger();
            AtomicInteger merged = new AtomicInteger();
            for (int i = 0; i < threads; i++) {
                String source = "src-" + i;
                String type = (i % 2 == 0) ? "bigint" : "string";
                pool.submit(() -> {
                    try {
                        start.await();
                        RegistrationResult r = roundReg.register(new RegistrationRequest(
                                source, "db.conflict", "TABLE",
                                List.of(new FieldClaim("id", type))));
                        // 立即响应的 ACCEPTED/MERGED/REJECTED 序列可随线程调度变化，
                        // 仅做计数留痕；最终去向以下面的收敛断言为准。
                        if (r.decision() == Decision.ACCEPTED) {
                            accepted.incrementAndGet();
                        } else if (r.decision() == Decision.MERGED) {
                            merged.incrementAndGet();
                        } else if (r.reason() == RejectReason.TYPE_CONFLICT) {
                            conflict.incrementAndGet();
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            FieldDef id = roundStore.getEntity("db.conflict").getFields().get("id");
            assertNotNull(id.getCanonicalType());
            assertEquals(1, accepted.get(), "第" + round + "轮：恰好一个请求创建实体");
            // 内容裁决（规范类型枚举名字典序最小，BIGINT<STRING）：无论线程调度如何，
            // 规范类型恒为 BIGINT，每个来源的最终去向完全一致——与“谁先到”无关。
            assertEquals(DataType.BIGINT, id.getCanonicalType(),
                    "第" + round + "轮：胜出规范类型必须与调度无关，恒为 BIGINT");
            assertEquals(expectedWinners, new java.util.TreeSet<>(id.getSources().keySet()),
                    "第" + round + "轮：6 个 bigint 来源恒为胜出去向");
            assertEquals(expectedLosers, new java.util.TreeSet<>(id.getLosingClaims().keySet()),
                    "第" + round + "轮：6 个 string 来源恒为落败去向，不污染规范定义");
            assertEquals(expectedWinners, new java.util.TreeSet<>(roundStore.getEntity("db.conflict").getSources()),
                    "第" + round + "轮：实体级来源集合同样按最终裁决收敛");
            if (round == 0) {
                log.info("用例并发冲突(内容裁决) 线程数={} 立即响应计数 ACCEPTED={} MERGED={} TYPE_CONFLICT={}（随调度变化）"
                                + " 最终规范类型={} 胜出去向={} 落败去向={}（20轮恒定）",
                        threads, accepted.get(), merged.get(), conflict.get(), id.getCanonicalType(),
                        id.getSources().keySet(), id.getLosingClaims().keySet());
            }
        }
    }
}
