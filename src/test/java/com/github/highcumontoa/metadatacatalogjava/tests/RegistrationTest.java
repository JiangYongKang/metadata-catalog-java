package com.github.highcumontoa.metadatacatalogjava.tests;

import com.github.highcumontoa.metadatacatalogjava.model.DataType;
import com.github.highcumontoa.metadatacatalogjava.model.EntityDefinition;
import com.github.highcumontoa.metadatacatalogjava.model.SourceDeclaration;
import com.github.highcumontoa.metadatacatalogjava.result.RegistrationResult;
import com.github.highcumontoa.metadatacatalogjava.result.RegistrationStatus;
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

/**
 * Registration: normalization, distinguishable rejection, idempotency,
 * conflict merging and deterministic concurrent registration.
 */
class RegistrationTest {

    private static final Logger log = LoggerFactory.getLogger(RegistrationTest.class);

    private MetadataCatalogService catalog;
    private AuditService audit;

    private static SourceDeclaration decl(String source, String entity, String kind,
                                          SourceDeclaration.FieldDeclaration... fields) {
        return new SourceDeclaration(source, entity, kind, List.of(fields));
    }

    private static SourceDeclaration.FieldDeclaration f(String name, String type) {
        return new SourceDeclaration.FieldDeclaration(name, type, true);
    }

    @BeforeEach
    void setUp() {
        audit = new AuditService();
        LineageService lineage = new LineageService(audit);
        catalog = new MetadataCatalogService(audit, lineage, new SchemaCompatibilityService());
    }

    @Test
    void normalizesAliasesCaseAndDuplicates() {
        RegistrationResult r1 = catalog.register(
                decl("hive", "Orders", "table", f("ID", "bigint"), f("Note", "VARCHAR(255)")), "tester");
        log.info("[normalizesAliasesCaseAndDuplicates] input=hive/Orders bigint,VARCHAR(255) -> status={}", r1.status());
        assertEquals(RegistrationStatus.REGISTERED, r1.status());

        RegistrationResult r2 = catalog.register(
                decl("mysql", "orders", "TABLE", f("id", "long"), f("note", "text")), "tester");
        log.info("[normalizesAliasesCaseAndDuplicates] input=mysql/orders long,text -> status={}", r2.status());
        assertEquals(RegistrationStatus.MERGED, r2.status(),
                "a second source adds provenance even when shapes agree");

        RegistrationResult r3 = catalog.register(
                decl("mysql", "orders", "TABLE", f("id", "long"), f("note", "text")), "tester");
        log.info("[normalizesAliasesCaseAndDuplicates] repeat mysql declaration -> status={}", r3.status());
        assertEquals(RegistrationStatus.DUPLICATE, r3.status(),
                "repeating the identical declaration is idempotent");

        EntityDefinition e = catalog.get("ORDERS");
        assertEquals(DataType.LONG, e.fields().get("id").dataType());
        assertEquals(DataType.STRING, e.fields().get("note").dataType());
        assertEquals(2, e.sources().size(), "both sources merged onto one entity");
        assertFalse(e.fields().get("id").conflicted());
    }

    @Test
    void rejectsBadInputWithDistinctReasons() {
        RegistrationResult blankSource = catalog.register(decl("  ", "e", "table"), "tester");
        log.info("[rejectsBadInput] blank source -> {} reason={}", blankSource.status(), blankSource.rejectReason());
        assertEquals(RejectReason.BLANK_SOURCE, blankSource.rejectReason());

        RegistrationResult blankEntity = catalog.register(decl("s", " ", "table"), "tester");
        assertEquals(RejectReason.BLANK_ENTITY_NAME, blankEntity.rejectReason());

        RegistrationResult blankField = catalog.register(
                decl("s", "e", "table", f(" ", "int")), "tester");
        assertEquals(RejectReason.BLANK_FIELD_NAME, blankField.rejectReason());

        RegistrationResult badType = catalog.register(
                decl("s", "e", "table", f("x", "weirdtype")), "tester");
        log.info("[rejectsBadInput] illegal type -> reason={} message={}", badType.rejectReason(), badType.message());
        assertEquals(RejectReason.ILLEGAL_FIELD_TYPE, badType.rejectReason());

        assertNull(catalog.get("e"), "rejected registration must not create an entity");
    }

    @Test
    void rejectsKindMismatchButKeepsOriginal() {
        catalog.register(decl("hive", "orders", "table", f("id", "bigint")), "tester");
        RegistrationResult r = catalog.register(decl("kafka", "orders", "topic"), "tester");
        log.info("[kindMismatch] input kind=topic -> reason={} message={}", r.rejectReason(), r.message());
        assertEquals(RegistrationStatus.REJECTED, r.status());
        assertEquals(RejectReason.KIND_MISMATCH, r.rejectReason());
        assertEquals(com.github.highcumontoa.metadatacatalogjava.model.EntityKind.TABLE,
                catalog.get("orders").kind());
    }

    @Test
    void mergesNewFieldsAndMarksTypeConflictWithoutOverwriting() {
        catalog.register(decl("hive", "orders", "table", f("id", "bigint"), f("amount", "decimal(10,2)")), "tester");
        RegistrationResult r = catalog.register(
                decl("mysql", "orders", "table", f("id", "bigint"), f("amount", "varchar(32)"),
                        f("new_col", "int")), "tester");
        log.info("[conflictMerge] status={} conflicts={} message={}", r.status(), r.conflictFields(), r.message());
        assertEquals(RegistrationStatus.CONFLICT, r.status());
        assertEquals(List.of("amount"), r.conflictFields());

        EntityDefinition e = catalog.get("orders");
        assertTrue(e.fields().get("amount").conflicted(), "conflicting field is marked, not overwritten");
        assertEquals("{hive=decimal(10,2), mysql=varchar(32)}",
                e.fields().get("amount").declaredTypes().toString(), "all raw claims preserved");
        assertTrue(e.hasField("new_col"), "non-conflicting new field still merged");

        // a third source agreeing with one side does not erase the conflict;
        // only when ALL sources agree does the marker clear
        RegistrationResult r3 = catalog.register(
                decl("glue", "orders", "table", f("amount", "numeric")), "tester");
        log.info("[conflictMerge] third source 'numeric' -> status={} conflicts={}",
                r3.status(), r3.conflictFields());
        assertEquals(RegistrationStatus.CONFLICT, r3.status());
        assertTrue(catalog.get("orders").fields().get("amount").conflicted());
    }

    @Test
    void concurrentRegistrationsConvergeToOneDeterministicSnapshot() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // half the sources claim bigint, half claim varchar for the same field
                    String type = idx % 2 == 0 ? "bigint" : "varchar(16)";
                    String source = "src-" + String.format("%02d", idx);
                    RegistrationResult r = catalog.register(
                            decl(source, "ConcurrentEntity", "table", f("id", type)), "concurrent-tester");
                    log.info("[concurrent] source={} type={} -> {}", source, type, r.status());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        EntityDefinition e = catalog.get("concurrententity");
        assertEquals(threads, e.sources().size(), "every concurrent source recorded exactly once");
        FieldShim id = new FieldShim(e.fields().get("id"));
        assertTrue(id.conflicted, "divergent claims leave an explainable conflict");
        // deterministic winner: lexicographically smallest source 'src-00' -> bigint -> LONG
        assertEquals(DataType.LONG, id.dataType, "conflict winner must be content-based, not timing-based");
        assertEquals(16, id.declaredTypesSize, "no claim lost under concurrency");
    }

    /** tiny accessor shim so the asserts read clearly. */
    private record FieldShim(boolean conflicted, DataType dataType, int declaredTypesSize) {
        FieldShim(com.github.highcumontoa.metadatacatalogjava.model.FieldDefinition f) {
            this(f.conflicted(), f.dataType(), f.declaredTypes().size());
        }
    }
}
