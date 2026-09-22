package com.github.highcumontoa.metadatacatalogjava.service;

import com.github.highcumontoa.metadatacatalogjava.model.DataType;
import com.github.highcumontoa.metadatacatalogjava.model.EntityDefinition;
import com.github.highcumontoa.metadatacatalogjava.model.EntityKind;
import com.github.highcumontoa.metadatacatalogjava.model.FieldDefinition;
import com.github.highcumontoa.metadatacatalogjava.model.SourceDeclaration;
import com.github.highcumontoa.metadatacatalogjava.result.ChangeResult;
import com.github.highcumontoa.metadatacatalogjava.result.ChangeStatus;
import com.github.highcumontoa.metadatacatalogjava.result.RegistrationResult;
import com.github.highcumontoa.metadatacatalogjava.result.RegistrationStatus;
import com.github.highcumontoa.metadatacatalogjava.result.RejectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local metadata catalog.
 * <p>
 * Source declarations of the same entity are normalized into one canonical
 * {@link EntityDefinition}. Bad input is rejected with a distinguishable
 * {@link RejectReason}; type disagreement between sources is <b>merged with a
 * conflict marker</b> rather than silently overwritten; identical re-declarations
 * are idempotent ({@link RegistrationStatus#DUPLICATE}).
 * <p>
 * Registrations of the same entity are serialized on a per-qualified-name lock,
 * and conflict resolution is content-based (the claim of the lexicographically
 * smallest source id wins as the displayed type), so concurrent and repeated
 * input always converges to the same deterministic snapshot.
 */
@Service
public class MetadataCatalogService {

    private static final Logger log = LoggerFactory.getLogger(MetadataCatalogService.class);

    private final AuditService auditService;
    private final LineageService lineageService;
    private final SchemaCompatibilityService compatibility;

    private final ConcurrentHashMap<String, EntityDefinition> entities = new ConcurrentHashMap<>();
    /** One lock object per qualified name; registration/change of an entity is serialized. */
    private final Map<String, Object> entityLocks = Collections.synchronizedMap(new HashMap<>());

    public MetadataCatalogService(AuditService auditService,
                                  LineageService lineageService,
                                  SchemaCompatibilityService compatibility) {
        this.auditService = auditService;
        this.lineageService = lineageService;
        this.compatibility = compatibility;
    }

    public RegistrationResult register(SourceDeclaration declaration, String actor) {
        log.info("REGISTER input source={} entity={} kind={} fieldCount={} actor={}",
                declaration.source(), declaration.entity(), declaration.kind(),
                declaration.fields() == null ? 0 : declaration.fields().size(), actor);

        // ---- input validation (distinguishable rejection) ----
        String source = declaration.source() == null ? null : declaration.source().trim();
        if (source == null || source.isBlank()) {
            return rejectRegistration(RejectReason.BLANK_SOURCE,
                    "source identifier must not be blank", actor, declaration);
        }
        String qn = normalize(declaration.entity());
        if (qn.isEmpty()) {
            return rejectRegistration(RejectReason.BLANK_ENTITY_NAME,
                    "entity name must not be blank", actor, declaration);
        }
        for (SourceDeclaration.FieldDeclaration f : nullSafeFields(declaration)) {
            if (f.name() == null || f.name().isBlank()) {
                return rejectRegistration(RejectReason.BLANK_FIELD_NAME,
                        "field name must not be blank (source=" + source + ")", actor, declaration);
            }
            if (!DataType.isLegal(f.rawType())) {
                return rejectRegistration(RejectReason.ILLEGAL_FIELD_TYPE,
                        "illegal field type '" + f.rawType() + "' for field '"
                                + f.name() + "' (source=" + source + ")", actor, declaration);
            }
        }
        EntityKind declaredKind = EntityKind.parse(declaration.kind());

        Object lock = entityLocks.computeIfAbsent(qn, k -> new Object());
        synchronized (lock) {
            EntityDefinition existing = entities.get(qn);

            if (existing != null && declaredKind != null && declaredKind != existing.kind()) {
                String msg = "kind conflict for entity " + qn + ": existing=" + existing.kind()
                        + " declared=" + declaredKind + " (source=" + source + ")";
                auditService.record("REGISTER", qn, actor, false,
                        RejectReason.KIND_MISMATCH.name(), msg);
                log.info("REGISTER decision REJECTED reason=KIND_MISMATCH entity={}", qn);
                return RegistrationResult.rejected(RejectReason.KIND_MISMATCH, msg);
            }

            boolean newEntity = existing == null;
            EntityKind kind = newEntity ? (declaredKind == null ? EntityKind.TABLE : declaredKind)
                    : existing.kind();

            SortedMap<String, FieldDefinition> fields = new TreeMap<>();
            TreeSet<String> sources = new TreeSet<>();
            if (existing != null) {
                // deep-copy the prior snapshot into mutable builders
                for (FieldDefinition f : existing.fields().values()) {
                    fields.put(f.name(), f.withConflict(f.conflicted(), f.dataType()));
                }
                sources.addAll(existing.sources());
            }

            boolean contentChanged = false;
            boolean anyConflict = false;
            List<String> conflictFields = new ArrayList<>();

            for (SourceDeclaration.FieldDeclaration fd : nullSafeFields(declaration)) {
                String fname = normalize(fd.name());
                DataType canonical = DataType.parse(fd.rawType());
                FieldDefinition current = fields.get(fname);

                if (current == null) {
                    SortedMap<String, String> declared = new TreeMap<>();
                    declared.put(source, fd.rawType().trim());
                    FieldDefinition built = new FieldDefinition(
                            fname, fd.name().trim(), canonical, fd.nullable(),
                            new TreeSet<>(List.of(source)), declared, false);
                    fields.put(fname, built);
                    contentChanged = true;
                    continue;
                }

                // merge into the known field
                boolean sourceAdded = current.sources().add(source);
                String previousRaw = current.declaredTypes().put(source, fd.rawType().trim());
                boolean nullable = current.nullable() || fd.nullable();

                // recompute conflict from ALL recorded claims; deterministic winner:
                // claim of the lexicographically smallest source id is displayed
                boolean conflict = false;
                DataType firstClaim = null;
                for (String rawClaim : current.declaredTypes().values()) {
                    DataType t = DataType.parse(rawClaim);
                    if (firstClaim == null) {
                        firstClaim = t;
                    } else if (t != firstClaim) {
                        conflict = true;
                        break;
                    }
                }
                String winner = current.declaredTypes().firstKey();
                DataType winnerType = DataType.parse(current.declaredTypes().get(winner));
                boolean fieldChanged = sourceAdded
                        || !java.util.Objects.equals(previousRaw, fd.rawType().trim())
                        || nullable != current.nullable()
                        || conflict != current.conflicted()
                        || winnerType != current.dataType();
                fields.put(fname, new FieldDefinition(
                        current.name(), current.displayName(), winnerType, nullable,
                        current.sources(), current.declaredTypes(), conflict));
                if (fieldChanged) {
                    contentChanged = true;
                }
                if (conflict) {
                    anyConflict = true;
                    conflictFields.add(fname);
                    log.info("REGISTER field conflict entity={} field={} claims={} canonicalWinner={}:{}",
                            qn, fname, current.declaredTypes(), winner, winnerType);
                }
            }

            if (sources.add(source)) {
                contentChanged = true;
            }

            if (!newEntity) {
                // retain prior conflict markers for fields this declaration did not touch,
                // and collect them for the result
                for (FieldDefinition f : fields.values()) {
                    if (f.conflicted() && !conflictFields.contains(f.name())) {
                        anyConflict = true;
                        conflictFields.add(f.name());
                    }
                }
            }

            EntityDefinition merged = new EntityDefinition(qn,
                    existing == null ? declaration.entity().trim() : existing.displayName(),
                    kind, fields, sources,
                    existing != null && existing.deprecated(),
                    existing != null && existing.readOnly());

            RegistrationStatus status;
            String message;
            if (newEntity) {
                status = RegistrationStatus.REGISTERED;
                message = "entity registered from source " + source;
                lineageService.registerEndpoint(qn);
            } else if (anyConflict) {
                status = RegistrationStatus.CONFLICT;
                message = "declaration merged with conflicts on fields: "
                        + String.join(", ", conflictFields)
                        + "; conflicting claims are preserved, nothing overwritten";
            } else if (contentChanged) {
                status = RegistrationStatus.MERGED;
                message = "declaration merged into existing entity " + qn;
            } else {
                status = RegistrationStatus.DUPLICATE;
                message = "identical declaration already known; no change";
            }

            entities.put(qn, merged);
            auditService.record("REGISTER", qn, actor, true, status.name(),
                    message + " | sources=" + sources + " fields=" + fields.size()
                            + " conflicts=" + conflictFields);
            log.info("REGISTER decision={} entity={} sources={} conflicts={}",
                    status, qn, sources, conflictFields);
            return new RegistrationResult(status, null, message, merged,
                    List.copyOf(conflictFields));
        }
    }

    public EntityDefinition get(String qualifiedName) {
        return entities.get(normalize(qualifiedName));
    }

    public List<EntityDefinition> list() {
        return entities.values().stream()
                .sorted(java.util.Comparator.comparing(EntityDefinition::qualifiedName))
                .toList();
    }

    public ChangeResult addField(String qualifiedName, String fieldName,
                                 String rawType, boolean nullable, String actor) {
        String qn = normalize(qualifiedName);
        log.info("ADD_FIELD input entity={} field={} type={} nullable={} actor={}",
                qn, fieldName, rawType, nullable, actor);
        synchronized (lockFor(qn)) {
            EntityDefinition entity = entities.get(qn);
            if (entity == null) {
                return rejectChange(RejectReason.ENTITY_NOT_FOUND,
                        "entity not found: " + qn, "ADD_FIELD", qn, actor, null);
            }
            ChangeResult gate = lifecycleGate(entity, "ADD_FIELD", qn, actor);
            if (gate != null) {
                return gate;
            }
            if (fieldName == null || fieldName.isBlank()) {
                return rejectChange(RejectReason.BLANK_FIELD_NAME,
                        "field name must not be blank", "ADD_FIELD", qn, actor, entity);
            }
            if (!DataType.isLegal(rawType)) {
                return rejectChange(RejectReason.ILLEGAL_FIELD_TYPE,
                        "illegal field type '" + rawType + "'", "ADD_FIELD", qn, actor, entity);
            }
            String fname = normalize(fieldName);
            if (entity.hasField(fname)) {
                String msg = "field already exists: " + fname + "; no change (compatible)";
                auditService.record("ADD_FIELD", qn + "#" + fname, actor, true,
                        ChangeStatus.COMPATIBLE_NO_CHANGE.name(), msg);
                return new ChangeResult(ChangeStatus.COMPATIBLE_NO_CHANGE, null, msg, entity, entity);
            }
            SortedMap<String, FieldDefinition> fields = new TreeMap<>(entity.fields());
            fields.put(fname, new FieldDefinition(fname, fieldName.trim(), DataType.parse(rawType),
                    nullable, new TreeSet<>(List.of("manual")),
                    new TreeMap<>(Map.of("manual", rawType.trim())), false));
            EntityDefinition updated = entity.withFields(fields);
            entities.put(qn, updated);
            String msg = "field added (compatible): " + fname + " " + DataType.parse(rawType);
            auditService.record("ADD_FIELD", qn + "#" + fname, actor, true,
                    ChangeStatus.APPLIED.name(), msg);
            log.info("ADD_FIELD decision=APPLIED entity={} field={}", qn, fname);
            return new ChangeResult(ChangeStatus.APPLIED, null, msg, entity, updated);
        }
    }

    public ChangeResult removeField(String qualifiedName, String fieldName, String actor) {
        String qn = normalize(qualifiedName);
        log.info("REMOVE_FIELD input entity={} field={} actor={}", qn, fieldName, actor);
        synchronized (lockFor(qn)) {
            EntityDefinition entity = entities.get(qn);
            if (entity == null) {
                return rejectChange(RejectReason.ENTITY_NOT_FOUND,
                        "entity not found: " + qn, "REMOVE_FIELD", qn, actor, null);
            }
            ChangeResult gate = lifecycleGate(entity, "REMOVE_FIELD", qn, actor);
            if (gate != null) {
                return gate;
            }
            String fname = normalize(fieldName);
            if (!entity.hasField(fname)) {
                return rejectChange(RejectReason.FIELD_NOT_FOUND,
                        "field not found: " + fname, "REMOVE_FIELD", qn, actor, entity);
            }
            // uniform compatibility policy: field removal is always breaking
            String msg = "removing field '" + fname + "' is a breaking change; rejected by policy"
                    + " (old readers may still request it)";
            auditService.record("REMOVE_FIELD", qn + "#" + fname, actor, false,
                    RejectReason.BREAKING_FIELD_REMOVAL.name(), msg);
            log.info("REMOVE_FIELD decision=REJECTED reason=BREAKING_FIELD_REMOVAL entity={} field={}",
                    qn, fname);
            return ChangeResult.rejected(RejectReason.BREAKING_FIELD_REMOVAL, msg, entity);
        }
    }

    public ChangeResult changeFieldType(String qualifiedName, String fieldName,
                                        String newRawType, String actor) {
        String qn = normalize(qualifiedName);
        log.info("CHANGE_TYPE input entity={} field={} newType={} actor={}", qn, fieldName, newRawType, actor);
        synchronized (lockFor(qn)) {
            EntityDefinition entity = entities.get(qn);
            if (entity == null) {
                return rejectChange(RejectReason.ENTITY_NOT_FOUND,
                        "entity not found: " + qn, "CHANGE_TYPE", qn, actor, null);
            }
            ChangeResult gate = lifecycleGate(entity, "CHANGE_TYPE", qn, actor);
            if (gate != null) {
                return gate;
            }
            String fname = normalize(fieldName);
            FieldDefinition field = entity.fields().get(fname);
            if (field == null) {
                return rejectChange(RejectReason.FIELD_NOT_FOUND,
                        "field not found: " + fname, "CHANGE_TYPE", qn, actor, entity);
            }
            if (!DataType.isLegal(newRawType)) {
                return rejectChange(RejectReason.ILLEGAL_FIELD_TYPE,
                        "illegal field type '" + newRawType + "'", "CHANGE_TYPE", qn, actor, entity);
            }
            DataType target = DataType.parse(newRawType);
            if (field.dataType() == target) {
                String msg = "field type unchanged: " + target;
                auditService.record("CHANGE_TYPE", qn + "#" + fname, actor, true,
                        ChangeStatus.COMPATIBLE_NO_CHANGE.name(), msg);
                return new ChangeResult(ChangeStatus.COMPATIBLE_NO_CHANGE, null, msg, entity, entity);
            }
            if (!compatibility.isTypeChangeCompatible(field.dataType(), target)) {
                String msg = compatibility.explainTypeChange(field.dataType(), target)
                        + " (entity=" + qn + ", field=" + fname + ")";
                auditService.record("CHANGE_TYPE", qn + "#" + fname, actor, false,
                        RejectReason.BREAKING_TYPE_CHANGE.name(), msg);
                log.info("CHANGE_TYPE decision=REJECTED reason=BREAKING_TYPE_CHANGE {} {}->{}",
                        qn, field.dataType(), target);
                return ChangeResult.rejected(RejectReason.BREAKING_TYPE_CHANGE, msg, entity);
            }
            SortedMap<String, FieldDefinition> fields = new TreeMap<>(entity.fields());
            SortedMap<String, String> declared = new TreeMap<>(field.declaredTypes());
            declared.put("manual", newRawType.trim());
            fields.put(fname, new FieldDefinition(fname, field.displayName(), target, field.nullable(),
                    new TreeSet<>(field.sources()), declared, false));
            EntityDefinition updated = entity.withFields(fields);
            entities.put(qn, updated);
            String msg = compatibility.explainTypeChange(field.dataType(), target)
                    + " (entity=" + qn + ", field=" + fname + ")";
            auditService.record("CHANGE_TYPE", qn + "#" + fname, actor, true,
                    ChangeStatus.APPLIED.name(), msg);
            log.info("CHANGE_TYPE decision=APPLIED {} {}->{}", qn, field.dataType(), target);
            return new ChangeResult(ChangeStatus.APPLIED, null, msg, entity, updated);
        }
    }

    public ChangeResult deprecate(String qualifiedName, String actor) {
        String qn = normalize(qualifiedName);
        log.info("DEPRECATE input entity={} actor={}", qn, actor);
        synchronized (lockFor(qn)) {
            EntityDefinition entity = entities.get(qn);
            if (entity == null) {
                return rejectChange(RejectReason.ENTITY_NOT_FOUND,
                        "entity not found: " + qn, "DEPRECATE", qn, actor, null);
            }
            if (entity.deprecated()) {
                String msg = "entity already deprecated; no change";
                auditService.record("DEPRECATE", qn, actor, true,
                        ChangeStatus.COMPATIBLE_NO_CHANGE.name(), msg);
                return new ChangeResult(ChangeStatus.COMPATIBLE_NO_CHANGE, null, msg, entity, entity);
            }
            EntityDefinition updated = entity.withFlags(true, entity.readOnly());
            entities.put(qn, updated);
            String msg = "entity marked deprecated; subsequent write operations will be rejected";
            auditService.record("DEPRECATE", qn, actor, true, ChangeStatus.APPLIED.name(), msg);
            log.info("DEPRECATE decision=APPLIED entity={}", qn);
            return new ChangeResult(ChangeStatus.APPLIED, null, msg, entity, updated);
        }
    }

    public ChangeResult setReadOnly(String qualifiedName, boolean readOnly, String actor) {
        String qn = normalize(qualifiedName);
        log.info("SET_READONLY input entity={} readOnly={} actor={}", qn, readOnly, actor);
        synchronized (lockFor(qn)) {
            EntityDefinition entity = entities.get(qn);
            if (entity == null) {
                return rejectChange(RejectReason.ENTITY_NOT_FOUND,
                        "entity not found: " + qn, "SET_READONLY", qn, actor, null);
            }
            if (entity.readOnly() == readOnly) {
                String msg = "read-only flag already " + readOnly + "; no change";
                auditService.record("SET_READONLY", qn, actor, true,
                        ChangeStatus.COMPATIBLE_NO_CHANGE.name(), msg);
                return new ChangeResult(ChangeStatus.COMPATIBLE_NO_CHANGE, null, msg, entity, entity);
            }
            EntityDefinition updated = entity.withFlags(entity.deprecated(), readOnly);
            entities.put(qn, updated);
            String msg = "read-only flag set to " + readOnly;
            auditService.record("SET_READONLY", qn, actor, true,
                    ChangeStatus.APPLIED.name(), msg);
            log.info("SET_READONLY decision=APPLIED entity={} readOnly={}", qn, readOnly);
            return new ChangeResult(ChangeStatus.APPLIED, null, msg, entity, updated);
        }
    }

    public boolean removeEntity(String qualifiedName, String actor) {
        String qn = normalize(qualifiedName);
        log.info("REMOVE_ENTITY input entity={} actor={}", qn, actor);
        synchronized (lockFor(qn)) {
            boolean removed = entities.remove(qn) != null;
            int edges = lineageService.detachEntity(qn);
            auditService.record("REMOVE_ENTITY", qn, actor, removed,
                    removed ? "APPLIED" : "ENTITY_NOT_FOUND",
                    removed ? "entity removed and " + edges + " lineage edge(s) detached (no dangling edges)"
                            : "entity not found: " + qn);
            log.info("REMOVE_ENTITY decision={} entity={} edgesDetached={}",
                    removed ? "APPLIED" : "REJECTED", qn, edges);
            return removed;
        }
    }

    /** Normalize a name into the catalog qualified name (lower-case, trimmed). */
    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    // ---------------- internals ----------------

    private Object lockFor(String qn) {
        return entityLocks.computeIfAbsent(qn, k -> new Object());
    }

    private List<SourceDeclaration.FieldDeclaration> nullSafeFields(SourceDeclaration d) {
        return d.fields() == null ? List.of() : d.fields();
    }

    private ChangeResult lifecycleGate(EntityDefinition entity, String action,
                                       String qn, String actor) {
        if (entity.deprecated()) {
            String msg = action + " rejected: entity " + qn + " is deprecated";
            auditService.record(action, qn, actor, false,
                    RejectReason.ENTITY_DEPRECATED.name(), msg);
            log.info("{} decision=REJECTED reason=ENTITY_DEPRECATED entity={}", action, qn);
            return ChangeResult.rejected(RejectReason.ENTITY_DEPRECATED, msg, entity);
        }
        if (entity.readOnly()) {
            String msg = action + " rejected: entity " + qn + " is read-only (no modification permission)";
            auditService.record(action, qn, actor, false,
                    RejectReason.ENTITY_READ_ONLY.name(), msg);
            log.info("{} decision=REJECTED reason=ENTITY_READ_ONLY entity={}", action, qn);
            return ChangeResult.rejected(RejectReason.ENTITY_READ_ONLY, msg, entity);
        }
        return null;
    }

    private RegistrationResult rejectRegistration(RejectReason reason, String message,
                                                  String actor, SourceDeclaration d) {
        String target = normalize(d.entity()) + (d.source() == null ? "" : "(" + d.source().trim() + ")");
        auditService.record("REGISTER", target, actor, false, reason.name(), message);
        log.info("REGISTER decision=REJECTED reason={} detail={}", reason, message);
        return RegistrationResult.rejected(reason, message);
    }

    private ChangeResult rejectChange(RejectReason reason, String message, String action,
                                      String qn, String actor, EntityDefinition before) {
        auditService.record(action, qn, actor, false, reason.name(), message);
        log.info("{} decision=REJECTED reason={} entity={}", action, reason, qn);
        return ChangeResult.rejected(reason, message, before);
    }
}
