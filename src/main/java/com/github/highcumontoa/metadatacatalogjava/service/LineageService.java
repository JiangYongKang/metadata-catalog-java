package com.github.highcumontoa.metadatacatalogjava.service;

import com.github.highcumontoa.metadatacatalogjava.result.ImpactResult;
import com.github.highcumontoa.metadatacatalogjava.result.LineageResult;
import com.github.highcumontoa.metadatacatalogjava.result.LineageStatus;
import com.github.highcumontoa.metadatacatalogjava.result.RejectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Upstream/downstream lineage registry.
 * <p>
 * Data-flow direction: edge {@code upstream -> downstream}.
 * <ul>
 *   <li>self references and cycles are rejected (with the offending path);</li>
 *   <li>re-adding the same edge is idempotent ({@link LineageStatus#DUPLICATE}, no double count);</li>
 *   <li>all edges touching a removed entity are detached, so no dangling edges remain;</li>
 *   <li>impact queries return transitive downstream entities with shortest hop counts.</li>
 * </ul>
 * A read/write lock makes concurrent edge registrations serial and deterministic.
 */
@Service
public class LineageService {

    private static final Logger log = LoggerFactory.getLogger(LineageService.class);

    private final AuditService auditService;

    private final Set<String> endpoints = new TreeSet<>();
    /** downstreamAdj: entity -> entities directly downstream of it. */
    private final Map<String, Set<String>> downstreamAdj = new HashMap<>();
    /** upstreamAdj: entity -> entities directly upstream of it. */
    private final Map<String, Set<String>> upstreamAdj = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public LineageService(AuditService auditService) {
        this.auditService = auditService;
    }

    /** Register edge: data flows from {@code upstream} into {@code downstream}. */
    public LineageResult addEdge(String upstreamRaw, String downstreamRaw, String actor) {
        String upstream = MetadataCatalogService.normalize(upstreamRaw);
        String downstream = MetadataCatalogService.normalize(downstreamRaw);
        log.info("LINEAGE input upstream={} downstream={} actor={}", upstream, downstream, actor);

        lock.writeLock().lock();
        try {
            if (!endpoints.contains(upstream) || !endpoints.contains(downstream)) {
                String missing = !endpoints.contains(upstream) ? upstream : downstream;
                String msg = "lineage endpoint not registered: " + missing;
                auditService.record("ADD_LINEAGE", upstream + "->" + downstream, actor,
                        false, RejectReason.LINEAGE_ENDPOINT_NOT_FOUND.name(), msg);
                log.info("LINEAGE decision REJECTED reason=LINEAGE_ENDPOINT_NOT_FOUND endpoint={}", missing);
                return LineageResult.rejected(RejectReason.LINEAGE_ENDPOINT_NOT_FOUND, msg, upstream, downstream);
            }

            if (upstream.equals(downstream)) {
                String msg = "self-referential lineage rejected: " + upstream + " -> " + downstream;
                auditService.record("ADD_LINEAGE", upstream + "->" + downstream, actor,
                        false, RejectReason.LINEAGE_SELF_REFERENCE.name(), msg);
                log.info("LINEAGE decision REJECTED reason=LINEAGE_SELF_REFERENCE");
                return LineageResult.rejected(RejectReason.LINEAGE_SELF_REFERENCE, msg, upstream, downstream);
            }

            // A new edge up -> down closes a cycle iff `down` can already reach `up`.
            List<String> cyclePath = findPath(downstream, upstream);
            if (cyclePath != null) {
                String path = String.join(" -> ", cyclePath) + " -> " + downstream;
                String msg = "cycle detected; adding " + upstream + " -> " + downstream
                        + " would close path " + path;
                auditService.record("ADD_LINEAGE", upstream + "->" + downstream, actor,
                        false, RejectReason.LINEAGE_CYCLE.name(), msg);
                log.info("LINEAGE decision REJECTED reason=LINEAGE_CYCLE path={}", path);
                return LineageResult.rejected(RejectReason.LINEAGE_CYCLE, msg, upstream, downstream);
            }

            Set<String> existing = downstreamAdj.get(upstream);
            if (existing != null && existing.contains(downstream)) {
                String msg = "lineage edge already exists: " + upstream + " -> " + downstream
                        + "; registration ignored (no duplicate count)";
                auditService.record("ADD_LINEAGE", upstream + "->" + downstream, actor,
                        true, LineageStatus.DUPLICATE.name(), msg);
                log.info("LINEAGE decision DUPLICATE edge={}->{} (not counted again)", upstream, downstream);
                return new LineageResult(LineageStatus.DUPLICATE, null, msg, upstream, downstream);
            }

            downstreamAdj.computeIfAbsent(upstream, k -> new TreeSet<>()).add(downstream);
            upstreamAdj.computeIfAbsent(downstream, k -> new TreeSet<>()).add(upstream);
            String msg = "lineage edge added: " + upstream + " -> " + downstream;
            auditService.record("ADD_LINEAGE", upstream + "->" + downstream, actor,
                    true, LineageStatus.ADDED.name(), msg);
            log.info("LINEAGE decision ADDED edge={}->{}", upstream, downstream);
            return new LineageResult(LineageStatus.ADDED, null, msg, upstream, downstream);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Transitive downstream impact of {@code rootRaw} with shortest hop counts.
     * Order is deterministic (sorted by qualified name).
     */
    public ImpactResult downstreamImpact(String rootRaw) {
        String root = MetadataCatalogService.normalize(rootRaw);
        lock.readLock().lock();
        try {
            SortedMap<String, Integer> hops = new TreeMap<>();
            if (!endpoints.contains(root)) {
                return new ImpactResult(root, List.of(), hops);
            }
            Deque<String> queue = new ArrayDeque<>();
            queue.add(root);
            hops.put(root, 0);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                int d = hops.get(cur);
                Set<String> next = downstreamAdj.get(cur);
                if (next == null) {
                    continue;
                }
                for (String n : new TreeSet<>(next)) {
                    if (!hops.containsKey(n)) {
                        hops.put(n, d + 1);
                        queue.add(n);
                    }
                }
            }
            List<String> impacted = hops.keySet().stream()
                    .filter(n -> !n.equals(root))
                    .toList();
            return new ImpactResult(root, impacted, hops);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<String> upstreamOf(String entityRaw) {
        String entity = MetadataCatalogService.normalize(entityRaw);
        lock.readLock().lock();
        try {
            Set<String> s = upstreamAdj.get(entity);
            return s == null ? List.of() : List.copyOf(new TreeSet<>(s));
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<String> downstreamOf(String entityRaw) {
        String entity = MetadataCatalogService.normalize(entityRaw);
        lock.readLock().lock();
        try {
            Set<String> s = downstreamAdj.get(entity);
            return s == null ? List.of() : List.copyOf(new TreeSet<>(s));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Remove every edge touching the entity and forget the endpoint. */
    public int detachEntity(String qualifiedNameRaw) {
        String qualifiedName = MetadataCatalogService.normalize(qualifiedNameRaw);
        lock.writeLock().lock();
        try {
            int removed = 0;
            Set<String> downs = downstreamAdj.remove(qualifiedName);
            if (downs != null) {
                removed += downs.size();
                for (String d : downs) {
                    Set<String> ups = upstreamAdj.get(d);
                    if (ups != null) {
                        ups.remove(qualifiedName);
                    }
                }
            }
            Set<String> ups = upstreamAdj.remove(qualifiedName);
            if (ups != null) {
                for (String u : ups) {
                    Set<String> ds = downstreamAdj.get(u);
                    if (ds != null) {
                        ds.remove(qualifiedName);
                    }
                }
            }
            endpoints.remove(qualifiedName);
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean exists(String qualifiedName) {
        lock.readLock().lock();
        try {
            return endpoints.contains(MetadataCatalogService.normalize(qualifiedName));
        } finally {
            lock.readLock().unlock();
        }
    }

    public void registerEndpoint(String qualifiedNameRaw) {
        String qualifiedName = MetadataCatalogService.normalize(qualifiedNameRaw);
        lock.writeLock().lock();
        try {
            endpoints.add(qualifiedName);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * BFS shortest path from {@code from} to {@code to} in downstream direction.
     * Caller must hold the write lock.
     *
     * @return ordered node names including both ends, or null if unreachable.
     */
    private List<String> findPath(String from, String to) {
        if (from.equals(to)) {
            return List.of(from);
        }
        Map<String, String> prev = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(from);
        seen.add(from);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            Set<String> next = downstreamAdj.get(cur);
            if (next == null) {
                continue;
            }
            for (String n : new TreeSet<>(next)) {
                if (seen.add(n)) {
                    prev.put(n, cur);
                    if (n.equals(to)) {
                        List<String> path = new ArrayList<>();
                        for (String x = to; x != null; x = prev.get(x)) {
                            path.add(x);
                        }
                        java.util.Collections.reverse(path);
                        return path;
                    }
                    queue.add(n);
                }
            }
        }
        return null;
    }
}
