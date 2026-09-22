package com.github.highcumontoa.metadatacatalogjava.tests;

import com.github.highcumontoa.metadatacatalogjava.model.SourceDeclaration;
import com.github.highcumontoa.metadatacatalogjava.result.ChangeResult;
import com.github.highcumontoa.metadatacatalogjava.result.ChangeStatus;
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

import static org.junit.jupiter.api.Assertions.*;

class SchemaChangeTest {

    private static final Logger log = LoggerFactory.getLogger(SchemaChangeTest.class);

    private MetadataCatalogService catalog;
    private AuditService audit;

    @BeforeEach
    void setUp() {
        audit = new AuditService();
        LineageService lineage = new LineageService(audit);
        catalog = new MetadataCatalogService(audit, lineage, new SchemaCompatibilityService());
        catalog.register(new SourceDeclaration("hive", "orders", "table",
                List.of(new SourceDeclaration.FieldDeclaration("id", "int", false),
                        new SourceDeclaration.FieldDeclaration("amount", "bigint", true))), "tester");
    }

    @Test
    void addingFieldIsCompatible() {
        ChangeResult r = catalog.addField("orders", "tag", "varchar(16)", true, "tester");
        log.info("[addField] input orders.tag varchar(16) -> status={} message={}", r.status(), r.message());
        assertEquals(ChangeStatus.APPLIED, r.status());
        assertTrue(catalog.get("orders").hasField("tag"));
    }

    @Test
    void wideningTypeIsAllowedWithExplanation() {
        ChangeResult r = catalog.changeFieldType("orders", "id", "bigint", "tester");
        log.info("[widen] int->bigint -> status={} message={}", r.status(), r.message());
        assertEquals(ChangeStatus.APPLIED, r.status());

        ChangeResult again = catalog.changeFieldType("orders", "id", "bigint", "tester");
        assertEquals(ChangeStatus.COMPATIBLE_NO_CHANGE, again.status());
    }

    @Test
    void narrowingTypeIsBreakingAndRejectedWithReason() {
        ChangeResult r = catalog.changeFieldType("orders", "amount", "int", "tester");
        log.info("[narrow] bigint->int -> status={} reason={} message={}",
                r.status(), r.rejectReason(), r.message());
        assertEquals(ChangeStatus.REJECTED, r.status());
        assertEquals(RejectReason.BREAKING_TYPE_CHANGE, r.rejectReason());
        assertTrue(r.message().contains("breaking type change"), "reason must be explainable");

        ChangeResult toStr = catalog.changeFieldType("orders", "amount", "string", "tester");
        assertEquals(ChangeStatus.APPLIED, toStr.status(), "number->string is a widening");

        ChangeResult back = catalog.changeFieldType("orders", "amount", "int", "tester");
        log.info("[narrow2] string->int -> reason={}", back.rejectReason());
        assertEquals(RejectReason.BREAKING_TYPE_CHANGE, back.rejectReason());
    }

    @Test
    void removingFieldIsAlwaysBreaking() {
        ChangeResult r = catalog.removeField("orders", "id", "tester");
        log.info("[remove] input orders.id -> status={} reason={} message={}",
                r.status(), r.rejectReason(), r.message());
        assertEquals(ChangeStatus.REJECTED, r.status());
        assertEquals(RejectReason.BREAKING_FIELD_REMOVAL, r.rejectReason());
        assertTrue(catalog.get("orders").hasField("id"), "rejected removal leaves field in place");
    }

    @Test
    void deprecatedEntityRejectsWritesWithDistinctReason() {
        assertEquals(ChangeStatus.APPLIED, catalog.deprecate("orders", "owner").status());
        ChangeResult r = catalog.addField("orders", "x", "int", true, "other-team");
        log.info("[deprecated] addField after deprecate -> reason={} message={}", r.rejectReason(), r.message());
        assertEquals(RejectReason.ENTITY_DEPRECATED, r.rejectReason());

        ChangeResult r2 = catalog.changeFieldType("orders", "id", "bigint", "other-team");
        assertEquals(RejectReason.ENTITY_DEPRECATED, r2.rejectReason());

        ChangeResult r3 = catalog.removeField("orders", "id", "other-team");
        assertEquals(RejectReason.ENTITY_DEPRECATED, r3.rejectReason());
    }

    @Test
    void readOnlyEntityRejectsWritesWithDistinctReason() {
        assertEquals(ChangeStatus.APPLIED, catalog.setReadOnly("orders", true, "governance").status());
        ChangeResult r = catalog.changeFieldType("orders", "id", "bigint", "writer");
        log.info("[readonly] changeType on read-only -> reason={} message={}", r.rejectReason(), r.message());
        assertEquals(RejectReason.ENTITY_READ_ONLY, r.rejectReason());

        // reads remain available
        assertNotNull(catalog.get("orders"));
    }

    @Test
    void unknownEntityAndFieldGiveDistinctReasons() {
        ChangeResult r1 = catalog.addField("ghost", "x", "int", true, "tester");
        log.info("[missing] addField on ghost -> reason={}", r1.rejectReason());
        assertEquals(RejectReason.ENTITY_NOT_FOUND, r1.rejectReason());

        ChangeResult r2 = catalog.changeFieldType("orders", "ghost", "int", "tester");
        assertEquals(RejectReason.FIELD_NOT_FOUND, r2.rejectReason());
    }

    @Test
    void auditTrailCoversAppliedAndRejectedActions() {
        catalog.addField("orders", "tag", "string", true, "alice");
        catalog.removeField("orders", "id", "bob");
        catalog.changeFieldType("orders", "amount", "int", "bob");

        var recs = audit.byEntity("orders");
        log.info("[audit] records for orders:");
        recs.forEach(r -> log.info("  seq={} {} by {} allowed={} reason={} :: {}",
                r.seq(), r.action(), r.actor(), r.allowed(), r.reason(), r.detail()));

        assertTrue(recs.stream().anyMatch(r -> r.allowed() && r.action().equals("ADD_FIELD")));
        assertTrue(recs.stream().anyMatch(r -> !r.allowed()
                && r.reason().equals(RejectReason.BREAKING_FIELD_REMOVAL.name())));
        assertTrue(recs.stream().anyMatch(r -> !r.allowed()
                && r.reason().equals(RejectReason.BREAKING_TYPE_CHANGE.name())));
        assertTrue(recs.stream().anyMatch(r -> r.actor().equals("bob")), "actor is traceable");

        var all = audit.all();
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i).seq() > all.get(i - 1).seq(), "audit order is total");
        }
    }
}
