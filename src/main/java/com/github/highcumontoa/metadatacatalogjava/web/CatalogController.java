package com.github.highcumontoa.metadatacatalogjava.web;

import com.github.highcumontoa.metadatacatalogjava.dto.ImpactResult;
import com.github.highcumontoa.metadatacatalogjava.dto.LineageRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.LineageResult;
import com.github.highcumontoa.metadatacatalogjava.dto.RegistrationRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.RegistrationResult;
import com.github.highcumontoa.metadatacatalogjava.dto.SchemaChangeRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.SchemaChangeResult;
import com.github.highcumontoa.metadatacatalogjava.model.AuditRecord;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;
import com.github.highcumontoa.metadatacatalogjava.model.EntityDef;
import com.github.highcumontoa.metadatacatalogjava.service.CompatibilityService;
import com.github.highcumontoa.metadatacatalogjava.service.EntityAdminService;
import com.github.highcumontoa.metadatacatalogjava.service.LineageService;
import com.github.highcumontoa.metadatacatalogjava.service.PermissionService;
import com.github.highcumontoa.metadatacatalogjava.service.RegistrationService;
import com.github.highcumontoa.metadatacatalogjava.store.LocalMetadataStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 本地元数据目录 REST 接口（骨架，方法在后续回合接入服务）。 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final RegistrationService registrationService;
    private final LineageService lineageService;
    private final CompatibilityService compatibilityService;
    private final EntityAdminService entityAdminService;
    private final PermissionService permissionService;
    private final LocalMetadataStore store;

    public CatalogController(RegistrationService registrationService, LineageService lineageService,
                             CompatibilityService compatibilityService, EntityAdminService entityAdminService,
                             PermissionService permissionService, LocalMetadataStore store) {
        this.registrationService = registrationService;
        this.lineageService = lineageService;
        this.compatibilityService = compatibilityService;
        this.entityAdminService = entityAdminService;
        this.permissionService = permissionService;
        this.store = store;
    }

    @PostMapping("/entities")
    public RegistrationResult register(@RequestBody RegistrationRequest request) {
        return registrationService.register(request);
    }

    @GetMapping("/entities")
    public Map<String, EntityDef> listEntities() {
        return store.allEntities();
    }

    @PostMapping("/changes")
    public SchemaChangeResult change(@RequestBody SchemaChangeRequest request) {
        return compatibilityService.apply(request);
    }

    @PostMapping("/lineage")
    public LineageResult lineage(@RequestBody LineageRequest request) {
        return lineageService.registerLineage(request);
    }

    @GetMapping("/impact/downstream/{qualifiedName}")
    public ImpactResult impact(@PathVariable String qualifiedName) {
        return lineageService.downstreamImpact(qualifiedName);
    }

    @PostMapping("/entities/{qualifiedName}/deprecate")
    public Map<String, String> deprecate(@PathVariable String qualifiedName,
                                         @RequestParam(defaultValue = "admin") String operator) {
        RejectReason reason = entityAdminService.deprecate(operator, qualifiedName);
        return reason == RejectReason.NONE
                ? Map.of("decision", "ACCEPTED", "qualifiedName", qualifiedName)
                : Map.of("decision", "REJECTED", "reason", reason.name());
    }

    @DeleteMapping("/entities/{qualifiedName}")
    public Map<String, String> remove(@PathVariable String qualifiedName,
                                      @RequestParam(defaultValue = "admin") String operator) {
        RejectReason reason = entityAdminService.remove(operator, qualifiedName);
        return reason == RejectReason.NONE
                ? Map.of("decision", "ACCEPTED", "qualifiedName", qualifiedName)
                : Map.of("decision", "REJECTED", "reason", reason.name());
    }

    @PostMapping("/permissions/{qualifiedName}")
    public Map<String, String> grant(@PathVariable String qualifiedName, @RequestParam String operator) {
        permissionService.grant(qualifiedName, operator);
        return Map.of("decision", "ACCEPTED", "qualifiedName", qualifiedName, "operator", operator);
    }

    @GetMapping("/audit")
    public List<AuditRecord> audit(@RequestParam(required = false) String qualifiedName) {
        return qualifiedName == null || qualifiedName.isBlank()
                ? store.allAudit()
                : store.auditTrail(com.github.highcumontoa.metadatacatalogjava.service.NameNormalizer
                        .normalize(qualifiedName));
    }
}
