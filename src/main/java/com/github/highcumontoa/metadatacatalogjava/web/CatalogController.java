package com.github.highcumontoa.metadatacatalogjava.web;

import com.github.highcumontoa.metadatacatalogjava.model.SourceDeclaration;
import com.github.highcumontoa.metadatacatalogjava.service.AuditService;
import com.github.highcumontoa.metadatacatalogjava.service.LineageService;
import com.github.highcumontoa.metadatacatalogjava.service.MetadataCatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST facade over the local metadata catalog and lineage services.
 * Rejected operations are returned with HTTP 422 and a distinguishable
 * reason code; nothing is silently dropped.
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final MetadataCatalogService catalog;
    private final LineageService lineage;
    private final AuditService audit;

    public CatalogController(MetadataCatalogService catalog,
                             LineageService lineage,
                             AuditService audit) {
        this.catalog = catalog;
        this.lineage = lineage;
        this.audit = audit;
    }

    @PostMapping("/register")
    public Object register(@RequestBody SourceDeclaration declaration,
                           @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor) {
        var result = catalog.register(declaration, actor);
        return ResponseEntity.status(switch (result.status()) {
            case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.OK;
        }).body(result);
    }

    @GetMapping("/entities")
    public List<?> entities() {
        return catalog.list();
    }

    @GetMapping("/entities/{name}")
    public ResponseEntity<?> entity(@PathVariable String name) {
        var e = catalog.get(name);
        return e == null
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "rejectReason", "ENTITY_NOT_FOUND", "message", "entity not found: " + name))
                : ResponseEntity.ok(e);
    }

    @PostMapping("/entities/{name}/fields")
    public Object addField(@PathVariable String name, @RequestBody Map<String, Object> body,
                           @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor) {
        var r = catalog.addField(name, str(body, "name"), str(body, "type"),
                bool(body, "nullable"), actor);
        return wrap(r.status().name().equals("REJECTED"), r);
    }

    @DeleteMapping("/entities/{name}/fields/{field}")
    public Object removeField(@PathVariable String name, @PathVariable String field,
                              @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor) {
        var r = catalog.removeField(name, field, actor);
        return wrap(r.status().name().equals("REJECTED"), r);
    }

    @PostMapping("/entities/{name}/fields/{field}/type")
    public Object changeType(@PathVariable String name, @PathVariable String field,
                             @RequestBody Map<String, Object> body,
                             @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor) {
        var r = catalog.changeFieldType(name, field, str(body, "type"), actor);
        return wrap(r.status().name().equals("REJECTED"), r);
    }

    @PostMapping("/entities/{name}/deprecate")
    public Object deprecate(@PathVariable String name,
                            @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor) {
        var r = catalog.deprecate(name, actor);
        return wrap(r.status().name().equals("REJECTED"), r);
    }

    @PostMapping("/entities/{name}/readonly")
    public Object readOnly(@PathVariable String name, @RequestBody Map<String, Object> body,
                           @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor) {
        var r = catalog.setReadOnly(name, bool(body, "readOnly"), actor);
        return wrap(r.status().name().equals("REJECTED"), r);
    }

    @DeleteMapping("/entities/{name}")
    public Object remove(@PathVariable String name,
                         @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor) {
        boolean removed = catalog.removeEntity(name, actor);
        return removed
                ? ResponseEntity.ok(Map.of("status", "REMOVED", "entity", name))
                : ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("rejectReason", "ENTITY_NOT_FOUND", "message", "entity not found: " + name));
    }

    @PostMapping("/lineage")
    public Object lineage(@RequestBody Map<String, String> body,
                          @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor) {
        var r = lineage.addEdge(body.get("upstream"), body.get("downstream"), actor);
        return wrap(r.status().name().equals("REJECTED"), r);
    }

    @GetMapping("/lineage/impact/{name}")
    public Object impact(@PathVariable String name) {
        return lineage.downstreamImpact(name);
    }

    @GetMapping("/audit")
    public Object audit(@RequestParam(value = "entity", required = false) String entity) {
        return entity == null ? audit.all() : audit.byEntity(entity);
    }

    private static Object wrap(boolean rejected, Object body) {
        return rejected
                ? ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body)
                : ResponseEntity.ok(body);
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : v.toString();
    }

    private static boolean bool(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof Boolean b && b;
    }
}
