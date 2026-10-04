package com.github.highcumontoa.metadatacatalogjava.tests;

import com.github.highcumontoa.metadatacatalogjava.dto.ImpactResult;
import com.github.highcumontoa.metadatacatalogjava.dto.LineageRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.LineageResult;
import com.github.highcumontoa.metadatacatalogjava.dto.RegistrationRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.FieldClaim;
import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 血缘服务单测：自引用 / 成环拒绝、幂等不重复计数、多跳影响、删除后无悬空血缘、并发确定。
 * 判定依据通过 [LINEAGE] / [IMPACT] 日志打印。
 */
class LineageServiceTest {

    private static final Logger log = LoggerFactory.getLogger(LineageServiceTest.class);

    private LocalMetadataStore store;
    private LineageService lineage;
    private EntityAdminService admin;

    @BeforeEach
    void setUp() {
        store = new LocalMetadataStore();
        var types = new TypeDictionary();
        var perms = new PermissionService();
        var registration = new RegistrationService(store, types, perms, new TypeArbitrator());
        lineage = new LineageService(store, perms);
        admin = new EntityAdminService(store, perms);
        for (String name : List.of("a", "b", "c", "d")) {
            registration.register(new RegistrationRequest("hive", name, "TABLE",
                    List.of(new FieldClaim("id", "bigint"))));
        }
    }

    @Test
    @DisplayName("自引用血缘被 SELF_REFERENCE 拒绝")
    void selfReferenceRejected() {
        var r = lineage.registerLineage(new LineageRequest("hive", "a", "a"));
        log.info("用例输入 a->a -> decision={}, reason={}, 依据={}", r.decision(), r.reason(), r.message());
        assertEquals(Decision.REJECTED, r.decision());
        assertEquals(RejectReason.SELF_REFERENCE, r.reason());
        assertEquals(0, store.allLineageEdges().size());
    }

    @Test
    @DisplayName("形成环的血缘被 CYCLE_DETECTED 拒绝并给出既有可达路径")
    void cycleRejected() {
        // a -> b -> c
        assertEquals(Decision.ACCEPTED, lineage.registerLineage(new LineageRequest("hive", "a", "b")).decision());
        assertEquals(Decision.ACCEPTED, lineage.registerLineage(new LineageRequest("hive", "b", "c")).decision());
        // c -> a 闭环
        var r = lineage.registerLineage(new LineageRequest("hive", "c", "a"));
        log.info("用例输入 c->a（既有 a->b->c） -> decision={}, reason={}, 依据={}",
                r.decision(), r.reason(), r.message());
        assertEquals(Decision.REJECTED, r.decision());
        assertEquals(RejectReason.CYCLE_DETECTED, r.reason());
        assertTrue(r.message().contains("a -> b -> c"), "必须给出形成环的既有可达路径");
        assertEquals(2, store.allLineageEdges().size(), "成环边不得写入");
    }

    @Test
    @DisplayName("重复登记同一条血缘：NO_CHANGE 且不重复计数（含并发）")
    void duplicateLineageNotCounted() throws InterruptedException {
        var req = new LineageRequest("hive", "a", "b");
        assertEquals(Decision.ACCEPTED, lineage.registerLineage(req).decision());
        assertEquals(Decision.NO_CHANGE, lineage.registerLineage(req).decision());
        assertEquals(Decision.NO_CHANGE, lineage.registerLineage(req).decision());
        assertEquals(1, store.allLineageEdges().size());

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger noChange = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    LineageResult r = lineage.registerLineage(new LineageRequest("hive", "a", "b"));
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
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        log.info("用例并发重复登记 a->b 线程数={} ACCEPTED={} NO_CHANGE={} 边总数={}",
                threads, accepted.get(), noChange.get(), store.allLineageEdges().size());
        assertEquals(0, accepted.get(), "并发重复下不应再产生 ACCEPTED");
        assertEquals(threads, noChange.get());
        assertEquals(1, store.allLineageEdges().size(), "重复血缘绝不重复计数");
    }

    @Test
    @DisplayName("并发成环竞态：同一缺口被两条边竞争关闭，至多一条 ACCEPTED，另一条成环被拒")
    void concurrentCloseCycle() throws InterruptedException {
        // 既有 a -> b -> c 与 a -> c，尝试登记 c->a（成环），并发 10 次
        lineage.registerLineage(new LineageRequest("hive", "a", "b"));
        lineage.registerLineage(new LineageRequest("hive", "b", "c"));
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger cycle = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    LineageResult r = lineage.registerLineage(new LineageRequest("hive", "c", "a"));
                    if (r.decision() == Decision.ACCEPTED) {
                        accepted.incrementAndGet();
                    } else if (r.reason() == RejectReason.CYCLE_DETECTED) {
                        cycle.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        log.info("用例并发登记成环边 c->a ACCEPTED={} CYCLE_DETECTED={}", accepted.get(), cycle.get());
        assertEquals(0, accepted.get(), "c->a 始终成环（a 已可达 c），任何调度下都不得写入");
        assertEquals(threads, cycle.get());
    }

    @Test
    @DisplayName("多跳下游影响按跳数分层返回")
    void multiHopImpact() {
        // a -> b -> d, a -> c
        lineage.registerLineage(new LineageRequest("hive", "a", "b"));
        lineage.registerLineage(new LineageRequest("hive", "a", "c"));
        lineage.registerLineage(new LineageRequest("hive", "b", "d"));
        ImpactResult impact = lineage.downstreamImpact("a");
        log.info("用例输入 origin=a -> hops={}, cycleMarked={}", impact.hops(), impact.cycleMarked());
        assertEquals(List.of("b", "c"), impact.hops().get(0).entities());
        assertEquals(List.of("d"), impact.hops().get(1).entities());
        assertFalse(impact.cycleMarked());
    }

    @Test
    @DisplayName("实体移除后关联血缘被级联清理，无悬空血缘")
    void removalCascadesLineage() {
        lineage.registerLineage(new LineageRequest("hive", "a", "b"));
        lineage.registerLineage(new LineageRequest("hive", "b", "c"));
        assertEquals(RejectReason.NONE, admin.remove("admin", "b"));
        log.info("用例移除实体 b 后剩余血缘={}（a/c 邻接均不得悬空）", store.allLineageEdges());
        assertEquals(0, store.allLineageEdges().size());
        assertTrue(store.downstreamOf("a").isEmpty());
        assertTrue(store.upstreamOf("c").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> lineage.downstreamImpact("b"));
    }

    @Test
    @DisplayName("上下游实体不存在：UNKNOWN_ENTITY 拒绝")
    void unknownEndpoints() {
        var r = lineage.registerLineage(new LineageRequest("hive", "a", "ghost"));
        log.info("用例输入 a->ghost -> reason={}", r.reason());
        assertEquals(RejectReason.UNKNOWN_ENTITY, r.reason());
    }
}
