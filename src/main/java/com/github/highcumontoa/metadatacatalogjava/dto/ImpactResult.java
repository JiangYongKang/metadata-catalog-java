package com.github.highcumontoa.metadatacatalogjava.dto;

import java.util.List;

/** 多跳影响查询结果，hops 为从给定实体可达的下游实体（按跳数层级返回）。 */
public record ImpactResult(String origin, List<Hop> hops, boolean cycleMarked) {

    /** 一跳层级：distance 为距 origin 的边数，entities 为该距离上的下游实体。 */
    public record Hop(int distance, List<String> entities) {
    }
}
