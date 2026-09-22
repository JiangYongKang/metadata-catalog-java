package com.github.highcumontoa.metadatacatalogjava.tests;

import com.github.highcumontoa.metadatacatalogjava.model.SourceDeclaration;
import com.github.highcumontoa.metadatacatalogjava.result.ImpactResult;
import com.github.highcumontoa.metadatacatalogjava.result.LineageResult;
import com.github.highcumontoa.metadatacatalogjava.result.LineageStatus;
import com.github.highcumontoa.metadatacatalogjava.result.RejectReason;
import com.github.highcumontoa.metadatacatalogjava.service.AuditService;
import com.github.highcumontoa.metadatacatalogjava.service.LineageService;
import com.github.highcumontoa.metadatacatalogjava.service.MetadataCatalogService;
import com.github.highcumontoa.metadatacatalogjava.service.SchemaCompatibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LineageTest {

    private static final Logger log = LoggerFactory.getLogger(LineageTest.class);

    private MetadataCatalogService catalog;
    private LineageService lineage;

    private void entity(String name) {
        catalog.register(new SourceDeclaration("s", name, "table", List.of()), "tester");
    }

    private LineageResult edge(String up, String down) {
        return lineage.addEdge(up, down, "tester");
    }

    @BeforeEach
    void setUp() {
        AuditService audit = new AuditService();
        lineage = new LineageService(audit);
        catalog = new MetadataCatalogService(audit, lineage, new SchemaCompatibilityService());
    }

    @Test
    void rejectsUnknownEndpointsAndSelfReference() {
        entity("a");
        LineageResult missing = edge("a", "ghost");
        log.info("[endpoint] input a->ghost -> status={} reason={} message={}",
                missing.status(), missing.rejectReason(), missing.message());
        assertEquals(LineageStatus.REJECTED, missing.status());
        assertEquals(RejectReason.LINEAGE_ENDPOINT_NOT_FOUND, missing.rejectReason());

        LineageResult self = edge("a", "A");
        log.info("[self] input a->A -> reason={} message={}", self.rejectReason(), self.message());
        assertEquals(RejectReason.LINEAGE_SELF_REFERENCE, self.rejectReason());
        assertTrue(lineage.downstreamOf("a").isEmpty());
    }

    @Test
    void detectsCyclesWithPath() {
        entity("a");
        entity("b");
        entity("c");
        assertEquals(LineageStatus.ADDED, edge("a", "b").status());
        assertEquals(LineageStatus.ADDED, edge("b", "c").status());

        LineageResult cycle = edge("c", "a");
        log.info("[cycle] input c->a -> reason={} message={}", cycle.rejectReason(), cycle.message());
        assertEquals(LineageStatus.REJECTED, cycle.status());
        assertEquals(RejectReason.LINEAGE_CYCLE, cycle.rejectReason());
        assertTrue(cycle.message().contains("cycle"), "decision must be explainable");

        // still a DAG: impact query unaffected
        ImpactResult impact = lineage.downstreamImpact("a");
        assertEquals(List.of("b", "c"), impact.downstream());
        assertEquals(2, impact.hops().get("c"));
    }

    @Test
    void duplicateEdgesCountedOnce() {
        entity("a");
        entity("b");
        edge("a", "b");
        LineageResult again = edge("A", "b"); // case-insensitive same edge
        log.info("[dup] repeat A->b -> status={} message={}", again.status(), again.message());
        assertEquals(LineageStatus.DUPLICATE, again.status());
        assertEquals(List.of("b"), lineage.downstreamOf("a"));
        assertEquals(1, lineage.downstreamOf("a").size(), "no duplicate counting");
    }

    @Test
    void multiHopImpactIsTransitive() {
        entity("a");
        entity("b");
        entity("c");
        entity("d");
        edge("a", "b");
        edge("b", "c");
        edge("c", "d");
        edge("a", "d"); // shortcut edge
        ImpactResult impact = lineage.downstreamImpact("a");
        log.info("[impact] root=a downstream={} hops={}", impact.downstream(), impact.hops());
        assertEquals(List.of("b", "c", "d"), impact.downstream());
        assertEquals(1, impact.hops().get("d"), "hops are shortest-path distances");
        assertEquals(2, impact.hops().get("c"));
    }

    @Test
    void removingEntityLeavesNoDanglingEdges() {
        entity("a");
        entity("b");
        entity("c");
        edge("a", "b");
        edge("b", "c");
        assertTrue(catalog.removeEntity("b", "tester"));
        log.info("[remove] b removed; a.downstream={} c.upstream={} b.exists={}",
                lineage.downstreamOf("a"), lineage.upstreamOf("c"), lineage.exists("b"));
        assertTrue(lineage.downstreamOf("a").isEmpty());
        assertTrue(lineage.upstreamOf("c").isEmpty());
        assertFalse(lineage.exists("b"));

        // re-adding an edge to the removed endpoint is rejected
        LineageResult r = edge("a", "b");
        assertEquals(RejectReason.LINEAGE_ENDPOINT_NOT_FOUND, r.rejectReason());
    }

    @Test
    void concurrentDuplicateEdgesProduceExactlyOne() throws Exception {
        entity("a");
        entity("b");
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicInteger added = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (edge("a", "b").status() == LineageStatus.ADDED) {
                        added.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        log.info("[concurrentDup] edge a->b attempted {} times, ADDED {} times", threads, added.get());
        assertEquals(1, added.get(), "exactly one writer registers the edge");
        assertEquals(1, lineage.downstreamOf("a").size());
    }
}
