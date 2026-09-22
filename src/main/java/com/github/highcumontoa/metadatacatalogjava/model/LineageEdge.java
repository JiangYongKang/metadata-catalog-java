package com.github.highcumontoa.metadatacatalogjava.model;

/** 一条有向血缘：upstream -> downstream。 */
public record LineageEdge(String upstream, String downstream, String source) {
}
