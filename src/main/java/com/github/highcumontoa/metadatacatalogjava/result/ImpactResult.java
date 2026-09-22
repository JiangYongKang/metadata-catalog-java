package com.github.highcumontoa.metadatacatalogjava.result;

import java.util.List;

/**
 * Multi-hop impact query result.
 *
 * @param root        queried entity
 * @param downstream  transitive downstream entities (multi-hop), deterministic order
 * @param hops        map of entity -> shortest hop distance from root
 */
public record ImpactResult(String root, List<String> downstream, java.util.SortedMap<String, Integer> hops) {
}
