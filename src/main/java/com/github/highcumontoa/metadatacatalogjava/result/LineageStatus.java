package com.github.highcumontoa.metadatacatalogjava.result;

public enum LineageStatus {
    /** New lineage edge created. */
    ADDED,
    /** Edge already existed; not counted again. */
    DUPLICATE,
    /** Rejected (cycle / self reference / missing endpoint). */
    REJECTED
}
